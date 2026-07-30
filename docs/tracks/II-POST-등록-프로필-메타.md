# II — POST 등록 프로필 메타 채움

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
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

**의도적으로 제외(구현 안 함)**:

| 필드 | 제외 이유 |
|---|---|
| `followers` | 단건 응답 `user` 노드에 `follower_count`가 없다(픽스처 전체 필드 열람으로 확인) — 채우려면 `/v2/user/by/username` 프로필 콜 +1이 필요해 제로 콜 원칙 위반. POST 전용 계정은 `profile_snapshot` 자체가 없어 계속 미수집. |
| `lastUploadedAt` | 정확값은 게시물 열거(`/v2/user/medias`) +2콜(clips 보강 포함)이 필요. 단건 응답은 게시물 1건의 게시일만 알아 "계정의 최근 게시일"(열거 전체 최댓값)을 대체할 수 없다. |

두 필드 모두 POST 전용 계정에서 계속 null로 남는다(계약 문서에 명시).

## 검증

`./gradlew :monitoring:test` 전체 통과(195건, 실패 0) — 신규 테스트: `HikerClientTest`(단건 파싱
owner 필드 2건 확장), `StoreTest`(`upsertOwnerFromPost` 신규 3건 — 신규 생성·last_uploaded_at
보존·null 인자 비파괴), `SnapshotWriterAlarmTest`(`savePost` 호출 후 profile_meta 행 생성·
공존 계정 회귀 방어 2건).

## 관련 문서

[monitoring-was-contract.md](../contracts/monitoring-was-contract.md) profile_meta 절(v2.2) —
POST 등록분도 `display_name`·`profile_image_url`이 채워진다는 점과 `followers`·`last_uploaded_at`
미수집 한계를 명시.
