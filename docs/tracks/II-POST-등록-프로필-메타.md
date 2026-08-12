# II — POST 등록 프로필 메타 채움

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/archive/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: 없음(monitoring `profile_meta`(트랙 S) 위에서 파싱 경로만 확장)
- **상태**: 🔨 (구현 완료 — PR 머지 대기)
- **트랙 문자 배정 메모**: FF/GG/HH가 각각 PR #235/#243/#236으로 미머지 상태라 그 다음 미사용 문자 II를 배정.

## 내용

**원인**: POST(게시물 URL) 모드로 등록한 캠페인은 `profile_meta` 행이 영구히 생성되지 않았다.
`SnapshotWriter.saveAccount`만 `profileMeta.upsert(...)`를 호출하고 `savePost`는 profile_meta를 안
건드렸으며, `DailySweepJob.needsEnumeration`은 그 계정에 ACCOUNT 타입 target이 하나라도 있을 때만
true라 POST 전용 등록 계정은 계정 갈래(`collectAccount`→`saveAccount`)를 영구히 안 탄다. was 서빙
쪽(`TrackingItemAssembler`)은 무죄 — `meta`가 null인 건 조인 키 문제가 아니라 순수 행 부재였다.

**해결(제로 콜 파싱)**: 단건 응답 `/v2/media/by/code`의 `items[0].user` 노드에 `full_name`·
`profile_pic_url`이 이미 실려 오는데(픽스처 실측 — `media-by-code.json`→sephora/Sephora,
`media-by-code-feed.json`→rarebeauty/Rare Beauty by Selena Gomez) `HikerClient.toPost`가 같은
노드에서 `username`만 뽑고 나머지를 버리고 있었다. **Hiker 콜을 단 1개도 늘리지 않고** 파싱만
넓혀 해결했다:

- `PostInfo`에 `ownerFullName`·`ownerProfilePicUrl` 2필드 추가(단건 응답에만 실값, 열거 경로는
  파싱은 되지만 소비하지 않음).
- `HikerClient.toPost`가 `user.full_name`·`user.profile_pic_url`을 무조건 파싱(분기 없음).
- `ProfileMetaRepository.upsertOwnerFromPost` 신규 — 기존 `upsert`(계정 갈래, 무조건 덮어씀)와
  달리 COALESCE로 null 인자가 기존 값을 지우지 않게 방어(같은 계정에 ACCOUNT·POST 캠페인이
  공존하면 같은 스윕에서 saveAccount 뒤에 savePost가 돌 수 있어서). `last_uploaded_at`은 컬럼
  목록에서 아예 뺐다 — 단건 경로는 게시물 1건의 taken_at만 알 뿐 계정 열거 전체 최댓값을 모른다.
- `SnapshotWriter.savePost`(공용 `savePostRow`가 아니라 여기)에서 upsert — savePostRow에 넣으면
  saveAccount의 게시물 순회에서 게시물 수만큼 중복 upsert가 돈다. 스윕이 매일 이 경로를 타므로
  인스타 CDN 서명 만료(~4일)도 자동 갱신된다 — monitoring의 profile_meta는 analytics 이미지
  아카이브 대상이 아니라 이 갱신이 유일한 만료 방어.
  **07-31 트랙 KK로 해소** — monitoring이 자체 프로필 이미지 아카이브 잡(`ProfileImageArchiveJob`)을
  붙여, 이 스윕 갱신과 별도로 종료(CANCELED/EXPIRED)된 캠페인까지 커버하는 영구 보존 경로가
  생겼다([KK-모니터링-프로필-이미지.md](KK-모니터링-프로필-이미지.md)).

**의도적으로 제외(구현 안 함)**:

| 필드 | 제외 이유 |
|---|---|
| `lastUploadedAt` | 정확값은 게시물 열거(`/v2/user/medias`) +2콜(clips 보강 포함)이 필요. 단건 응답은 게시물 1건의 게시일만 알아 "계정의 최근 게시일"(열거 전체 최댓값)을 대체할 수 없다. |

이 필드는 POST 전용 계정에서 계속 null로 남는다(계약 문서에 명시).

## 후속 결정(07-31) — followers는 "기록 없을 때 1회"로 수집

최초 구현 당시 `followers`도 위 표에 있었다("단건 응답에 `follower_count`가 없어 프로필 콜 +1
필요, 제로 콜 원칙 위반"이 사유). **사용자가 이 결정을 뒤집어 followers 수집을 승인** —
단, 조건이 붙었다: **매일 갱신이 아니라 "팔로워 기록이 아직 없는 계정만, 프로필을 1회 조회"**.
was가 서빙하는 `followers`는 `MonitoringReadRepository.findLatestProfileSnapshots`가 읽는
최신 1행 단일값이지 시계열이 아니라서, 매일 갱신할 실익이 없고 계정당 평생 약 1콜로 끝난다.

- 신설: `CollectService.collectProfileOnly`(프로필 1콜만, 열거 없음) →
  `SnapshotWriter.saveProfileOnly`(`@Transactional`, `profile_snapshot` upsert +
  `profileMeta.upsert(..., lastUploadedAt=null)` — COALESCE가 기존값 보존) →
  `SnapshotRepository.hasProfileSnapshot`(존재 여부 판정).
- `DailySweepJob.sweepAccount`가 `needsEnumeration`이 false인 갈래(POST 전용 계정)에서
  `hasProfileSnapshot`이 false일 때만 이 경로를 1회 호출한다.
- **best-effort**: 프로필 조회 실패는 try/catch로 전부 삼키고 `log.warn`만 남긴다
  (`PrivateAccountException`·`SubjectNotFoundException` 포함). 팔로워는 부가 표시 정보라 여기서
  예외가 새면 `sweepRound`의 catch가 그 계정의 캠페인을 통째로 hidden 전이시킨다 — 추적 게시물은
  멀쩡한데 프로필 조회 실패만으로 캠페인이 죽는 새 고장 경로가 생긴다. POST 등록분의 생존 판정은
  지금까지처럼 단건 게시물 수집 성공 여부 하나로만 유지한다.
- ACCOUNT 타입이 섞인 계정(`needsEnumeration`이 true)은 건드리지 않는다 — 지금처럼 매일
  `collectAccount`(프로필+열거)가 그대로 followers를 갱신한다.

**한계**: 값이 최초 수집 시점에 고정된다 — 캠페인 후반에도 계정의 첫 수집 시점 팔로워 수가
그대로 노출된다. 실패해도 best-effort라 캠페인 생존 판정에는 영향이 없다(수집이 안 됐을 뿐,
캠페인은 계속 살아있는 것으로 취급).

**되돌리는 법**: 이 후속 결정만 되돌리려면 신설 메서드 3개(`CollectService.collectProfileOnly`·
`SnapshotWriter.saveProfileOnly`·`SnapshotRepository.hasProfileSnapshot`)와
`DailySweepJob.sweepAccount`의 조건부 호출 블록(`collectProfileOnlyOnce`)만 지우면 된다 —
최초 구현(display_name·profile_image_url 제로 콜 파싱)과는 독립된 별도 커밋이라 분리 롤백이 가능하다.

## 검증

`./gradlew :monitoring:test` 전체 통과(210건, 실패 0) — 최초 구현(195건)에 이번 후속 결정의
신규 테스트 5건(`DailySweepJobTest` — 미보유 계정 1회 수집·기보유 계정 재호출 안 함·프로필 조회
실패 3종 best-effort·ACCOUNT 혼재 계정 중복 호출 안 함)이 더해졌다.

## 관련 문서

[monitoring-was-contract.md](../contracts/monitoring-was-contract.md) profile_meta 절(v2.2) —
POST 등록분도 `display_name`·`profile_image_url`이 채워진다는 점과, followers는 최초 1회만
수집되고 이후 갱신되지 않는다는 점, `last_uploaded_at` 미수집 한계를 명시.
