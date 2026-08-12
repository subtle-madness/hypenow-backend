# KK — 모니터링 프로필 이미지 아카이브·업스트림 검증

- **소속 트랙군**: 상세 분석 작업 트랙 — 구조 설계: [specs/2026-07-12-detail-analysis-design.md](../superpowers/specs/2026-07-12-detail-analysis-design.md) · 데이터 층(A·B1·F·B2·B3) 설계: [specs/2026-07-12-analytics-data-layer-design.md](../superpowers/specs/2026-07-12-analytics-data-layer-design.md)
- **의존**: 트랙 S(monitoring was seam)·트랙 II(POST 등록 profile_meta 채움) 위에서 동작. 트랙 J(서빙 이미지 아카이브)와는 **OCI 버킷만 공유하고 코드·후보군은 완전히 분리**(키 프리픽스로 소유권 구분 — 아래 참고).
- **상태**: 🔨 (결함 ② PR #277 머지 완료 · 결함 ① PR #278 머지 완료 · 게시물 썸네일 동형 확장 머지 완료 · 게시자(author_profile) 동형 확장 머지 완료 · 브랜드 본인(brand_account) 동형 확장 머지 완료 · 브랜드 게시물 썸네일 아카이브 + was 서빙 계약 일괄 적용 PR #415 머지 완료 · 기존 4개 잡의 배치 창 잠식 결함 수정 PR 오픈(아래 §배치 상한))
- **트랙 문자 배정 메모**: `docs/tracks/`에 FF·GG가 아직 파일로 없지만 다른 세션이 PR #235·#243으로 점유 중이고, A~JJ 중 KK가 다음 미사용 문자라 배정.

## 내용

07-31 모니터링 QA 실측으로 프로필 이미지 결함 2건을 확인했다 —
[specs/2026-07-31-monitoring-profile-image-design.md](../superpowers/specs/2026-07-31-monitoring-profile-image-design.md) 참조.

### 결함 ① — 종료된 항목의 이미지가 영구히 깨진다 (이 브랜치)

`TargetRepository.findActive()`가 `status IN ('WATCHING','TRACKING')`만 조회하므로 CANCELED·EXPIRED로
전이된 target은 스윕 대상에서 영구 제외되고, 그 순간부터 `profile_meta.profile_image_url`이 다시는
갱신되지 않는다. 인스타 CDN URL은 `oe=` 쿼리파라미터 기준 약 4일 뒤 만료(403)되므로, 종료 후 마지막
성공 스윕으로부터 4일이 지나면 그 항목의 프로필 이미지는 재생성 경로 없이 영구히 깨진 링크가 된다.
등록 목록은 종료돼도 삭제되지 않으므로 유저는 끝난 캠페인을 계속 열어본다 — 실사용에서 만난다.

**기존 자산(트랙 J `ImageArchiveJob`) 재사용을 기각한 근거**: 그 잡의 프로필 후보군은 `v_accounts`
(크롤러가 뷰티 인플루언서로 판정·분석 중인 계정)뿐인데, 모니터링은 유저가 임의의 IG 계정을 등록해
추적하는 기능이라 두 집합이 겹칠 구조적 이유가 없다. test monitoring DB `profile_meta` 19건 기준
정량 확인: analysis DB `image_assets`(kind='profile') 겹침 **0/19**(test는 아카이브 잡 미실행, 테이블
자체가 0행), analysis DB `accounts` 미러 겹침 **10/19(53%)**. 운영 monitoring DB는 `target` 0건(아직
실사용 유입이 없다), 운영 `image_assets`는 profile 7,116 / thumbnail 70,535건으로 정상 가동 중.
**표본이 작아 53%는 일반화할 수 없으나, "원리적으로 전수 커버가 불가능하다"는 점은 표본 크기와
무관**하다 — 후보군 확장으로는 결함 ①을 해소할 수 없다는 결론의 근거다.

**핵심 구조**: 아카이브 대상은 `target`이 아니라 **`profile_meta` 전체**다. `profile_meta`는
`username` PK로 target 상태와 무관하게 영구 존속하므로, `findActive()`를 전혀 건드리지 않고 종료
항목이 자동으로 커버된다. 종료된 항목은 마지막으로 관측된 프로필 이미지가 우리 저장소에 영구
보존된다.

```
DailySweepJob 종료
  └→ ProfileImageArchiveJob (monitoring 소유)
       profile_meta 스캔 → source_name(원본 URL 파일명) 변경분만
       → ImageDownloader(JDK HttpClient) → ParImageStore(OCI PUT)
       → UPDATE profile_meta SET image_object_path / image_source_name / image_archived_at

was: image_object_path 있으면 '/img/' || path, 없으면 원본 CDN 폴백
     (monitoring DB 단일 조회 — 크로스 DB 조인 없음)
```

**검토했으나 기각한 대안:**

| 대안 | 기각 사유 |
|---|---|
| analytics `ImageArchiveJob`이 monitoring DB를 읽도록 확장 | 시스템 경계 위반(analytics는 raw 읽기 + 분석 결과 쓰기 전용). 위 후보군 문제도 그대로 잔존 |
| 종료 항목도 주기적으로 Hiker 재조회 | 종료 캠페인이 누적될수록 Hiker 콜이 무한 증가하고, 여전히 CDN 만료에 종속 |
| 만료 추정 시 was가 null 서빙(플레이스홀더) | 구현은 간단하지만 이미지가 복원되지 않는다 — 종료된 캠페인의 프로필 사진이 영구히 안 보임 |

**세부 결정:**

- **스키마**: `profile_meta`에 nullable 3컬럼 추가 — `image_object_path`, `image_source_name`,
  `image_archived_at`(`V20260730192350__profile_meta_image_archive.sql`). additive라
  expand-contract의 expand 단계로 안전(롤링 중 구 코드는 무시).
- **키 스킴**: `monitor-profile/<username>.jpg`. analytics의 `profile/<handle>.jpg`와 프리픽스를
  분리해 같은 버킷 안에서 소유권이 충돌하지 않게 한다(핸들이 겹쳐도 서로 덮어쓰지 않음). 프론트
  `next.config.ts` rewrite는 `/img/:path*` 글롭이라 프론트 변경 불필요.
- **재다운로드 판정에서 쿼리스트링을 제외하는 이유**: 원본 URL의 경로 마지막 세그먼트(파일명)를
  `image_source_name`과 비교해 변경 여부를 판정한다. 쿼리스트링(`oe=`·서명)은 매 조회마다
  바뀌므로 반드시 제외해야 한다 — 안 그러면 파일명이 실제로는 그대로여도 서명값 차이 때문에
  매일 전량 재다운로드하게 된다. analytics `ImageArchiveJob.profileChanged`와 같은 관용구
  (`ProfileImageArchiveJob.sourceName`).
- **실행 시점을 스윕 종료 직후로 둔 이유**: 별도 크론이 아니라 `DailySweepJob.runWithId`의
  `finally` 블록에서 sweep_run 완료 기록 직후 마지막 단계로 돈다. (1) 갓 갱신된 신선한 URL을
  바로 잡을 수 있고, (2) 크론 설정이 한 곳(스윕 크론)으로 통제되며, (3) K 원칙상 test는 스윕
  크론이 꺼져 있어 수동 스윕 트리거(#260 `POST /api/sweeps`)로 아카이브 잡까지 함께 검증할 수
  있다. 스윕 성공/실패(`ok` true/false)와 무관하게 실행되고, 아카이브 잡 자체의 실패는
  `runProfileImageArchiveSafely()`가 전부 삼켜 밖으로 새지 않으므로 이미 기록된 `sweep_run.ok`를
  오염시키지 않는다(잡 전체를 격리 — 건 단위 격리와는 별도 층).
- **PAR 미설정 시 no-op**: `MONITORING_IMAGE_PAR_URL`이 비어 있으면 `ProfileImageArchiveJob.run()`이
  로그만 남기고 즉시 반환한다(기동 실패가 아니다). test 환경(`compose.test.yaml`의
  `MONITORING_IMAGE_PAR_URL: ""`)은 현행 동작 그대로 원본 CDN 폴백으로 서빙된다 — 운영 버킷 오염
  방지 목적도 겸한다.
- **건 단위 실패 격리**: 한 계정의 다운로드/업로드 실패(catch-log-continue)가 나머지 계정의
  아카이브를 막지 않는다. URL 파싱 실패(`sourceName`)도 별도로 잡아 스킵 — PR-1(스킴 정규화)
  이전에 저장된 변종이 `LIKE 'http%'`는 통과하면서 `getPath()`가 null이라 NPE를 낼 수 있는데,
  이게 루프 밖으로 새면 잡 전체가 중단되기 때문이다.
- **클래스 복제**: `ImageDownloader`/`ImageStore`/`ParImageStore`(~90줄, 순수 JDK, Spring 무의존)를
  `com.celfit.monitoring.image`로 복제한다. 계약 모듈 `contract-analysis`는 분석 결과의
  record·enum 전용이라 HTTP 어댑터를 넣을 수 없고, 모듈 간 import는 ARCHITECTURE §4-4가 금지한다
  — 90줄 중복이 새 공유 모듈 신설보다 원칙에 맞다는 판단.
- **배포**: `deploy/compose.yaml`의 monitoring 서비스에
  `MONITORING_IMAGE_PAR_URL: ${ANALYTICS_IMAGE_PAR_URL}`(같은 버킷·같은 쓰기 PAR 재사용),
  `compose.test.yaml`은 `""`.

### 결함 ② — 업스트림 값을 검증 없이 저장·서빙한다 (PR #277, 머지 완료)

`ProfileMetaRepository`가 Hiker 응답의 `profile_pic_url`을 스킴 검증 없이 저장하고, was
`TrackingItemAssembler`가 그 값을 raw로 응답에 실었다. test DB에서 Hiker가 리터럴 문자열
`"exception://"`(무효 URL)을 보낸 사례를 실측 확인했다(`cherish__sy`, 19건 중 1건).

- **저장 측(monitoring)**: 정규화를 `ProfileMetaRepository`의 두 upsert **진입점**에 둔다. 호출자
  3곳(`SnapshotWriter`)에 각각 넣으면 향후 호출자 추가 시 누락된다. `http://`/`https://`로
  시작하지 않으면 null로 강등.
- **계정 모드 upsert 시맨틱 통일**: 기존 `upsert`는 `profile_image_url = EXCLUDED.profile_image_url`
  무조건 덮어쓰기라, 정규화만 하면 `exception://`이 올 때 기존 유효값이 NULL로 날아간다. POST
  모드 `upsertOwnerFromPost`가 이미 쓰는 `COALESCE(EXCLUDED, 기존)` 보존 시맨틱으로
  **`profile_image_url` 한 컬럼만** 통일했다(`display_name`·`last_uploaded_at`은 현행 유지). 업스트림이
  일시적으로 쓰레기를 줘도 마지막 유효 이미지가 남는다.
- **서빙 측(was)**: `TrackingItemAssembler`에 얇은 가드(`sanitizeImageUrl`). 저장 측이 막아도 이미
  DB에 박힌 값이 있으므로 이중 방어.
- **기존 오염행**: monitoring Flyway 신규 마이그레이션(`V20260730191710__profile_image_url_scheme_cleanup.sql`)으로
  `profile_image_url !~ '^https?://'` 행을 NULL 정정.

### 게시물 썸네일 아카이브 (트랙 KK 확장, 2026-08-01 — 프로필 이미지와 동형)

`post_meta.thumbnail_url`도 `profile_meta.profile_image_url`과 같은 인스타 CDN 서명 만료(~4일) 문제를
그대로 겪는다 — `PostMetaRepository`도 종전엔 스킴 검증 없이 저장했고, 종료(CANCELED/EXPIRED)된
캠페인의 추적 게시물 썸네일은 아카이브 없이는 영구히 깨진다. **결함①·②와 완전히 같은 형태라
새 설계 없이 그대로 이식**했다.

- **스키마**: `post_meta`에 profile_meta와 동일한 3컬럼 — `image_object_path`, `image_source_name`,
  `image_archived_at`(`V20260801064345__post_meta_thumbnail_archive.sql`). 같은 마이그레이션 파일에
  `thumbnail_url !~* '^https?://'` 오염행 정정 UPDATE를 동봉했다(V20260730191710과 동형 — 별도 파일로
  안 쪼갠 이유: 이번 확장은 배포 시점에 이미 스킴 검증이 코드에 포함돼 나가므로 결함①·②처럼 먼저
  작은 PR로 검증부터 배포할 필요가 없다).
- **키 스킴**: `monitor-post/<short_code>.jpg`. `monitor-profile/`(이 트랙)·analytics `thumb/`(트랙 J)와
  프리픽스가 셋 다 분리돼 같은 버킷에서 소유권이 충돌하지 않는다.
- **저장 측 정규화**: `PostMetaRepository.upsert`는 애초에 진입점이 하나뿐이라(profile_meta처럼
  upsert/upsertOwnerFromPost 두 갈래로 나뉘지 않는다) `normalizeThumbnailUrl` 하나만 추가하면 된다.
  기존 COALESCE(EXCLUDED, 기존값) 보존 시맨틱은 이미 있었으므로(v2.2부터) 보존 시맨틱 통일 작업은
  불필요했다 — profile_meta 결함②에서 필요했던 "계정 갈래 upsert가 무조건 덮어쓰기였다"는 문제가
  post_meta에는 애초에 없었다.
- **아카이브 잡**: `ProfileImageArchiveJob`을 일반화하지 않고 `PostThumbnailArchiveJob`을 나란히
  추가했다(과도한 리팩터링 금지 지침) — 대상 테이블·컬럼·키 프리픽스만 다르고 나머지(배치 상한·
  건 단위 격리·source_name 재다운로드 판정·PAR 미설정 no-op)는 완전히 동형이다. `ImageDownloader`/
  `ImageStore`/`ParImageStore`는 이미 범용이라 그대로 재사용(추가 복제 없음). `DailySweepJob`의
  finally 블록에서 프로필 아카이브 직후 독립적으로 실행되고(하나가 실패해도 다른 하나는 그대로
  진행), `MONITORING_IMAGE_PAR_URL`·`monitoring.image.archive-batch-limit` 설정을 그대로 공유한다 —
  두 잡이 같은 버킷·같은 배치 상한 정책을 쓰는 것이 자연스러워 별도 env 분리는 하지 않았다.
- **was 서빙**: `TrackingItemAssembler.buildPost`가 `resolveThumbnailUrl`(resolveImageUrl과 동형)로
  `image_object_path` 우선 서빙 + `sanitizeImageUrl` 이중 방어를 적용한다. `MonitoringReadRepository
  .findPostMeta`·`PostMetaRow`에 `imageObjectPath` 추가.
- **판단이 갈린 지점**: 캐시 제어값을 analytics `ImageArchiveJob`의 썸네일 전용 immutable 값
  (`public, max-age=31536000, immutable`)이 아니라 프로필 이미지와 같은 `public, max-age=86400`으로
  맞췄다 — profile_meta와 "동형"을 유지하는 것이 이번 작업 범위이고, post 썸네일도 source_name
  비교로 변경 감지를 하는 이상(완전 불변으로 취급하지 않음) 짧은 캐시가 더 안전하다는 판단.

### 게시자 프로필 이미지 아카이브 (트랙 KK 확장, 2026-08-07 — author_profile)

브랜드 태그 모니터링(MON-BT, 08-06 개통)의 게시자 프로필 캐시 `author_profile.profile_pic_url`은
30일 stale + 재등장 시에만 재조회되는데(태그 스펙 §8) CDN 서명은 ~4일이면 만료된다 — 매일 스윕
upsert가 URL을 되살리는 `brand_post_meta.thumbnail_url`과 달리, **아카이브 없이는 저장된 게시자
프로필 사진이 대부분의 기간 죽어 있다**. 프로필 이미지·게시물 썸네일과 완전히 같은 형태라 새 설계
없이 그대로 이식했다.

- **스키마**: `author_profile`에 동일한 3컬럼 — `image_object_path`, `image_source_name`,
  `image_archived_at`(`V20260807150500__author_profile_image_archive.sql`). 오염행 정정 UPDATE는
  불필요 — author_profile은 08-06 신설 테이블이라 스킴 정규화 이전의 변종 축적이 없다(잡의
  `LIKE 'http%'` 필터 + RuntimeException 격리가 방어선).
- **키 스킴**: `monitor-author/<ig_user_id>.jpg` — username이 아니라 **ig_user_id(PK) 기준**.
  username은 개명 가능해서 키로 쓰면 개명 시 고아 오브젝트가 남는다(profile_meta는 username이
  PK라서 username 키가 맞았고, author_profile은 ig_user_id가 PK라 기준이 다르다).
- **실행 시점**: 캠페인 `DailySweepJob`이 아니라 **`BrandSweepJob.run()`의 finally** — author_profile을
  갱신하는 주체가 브랜드 스윕이므로, 갓 재조회된 신선한 URL을 바로 잡으려면 브랜드 스윕 직후여야
  한다. 격리 구조(잡 전체 격리 + 건 단위 격리)는 DailySweepJob 패턴 동형.
- **CDN 이미 만료된 잔존행**: 재조회 전까지 건 단위 실패로 격리되고 매일 재시도된다 — 다음
  재조회(30일 stale + 재등장)가 URL을 되살리면 그때 아카이브된다. 수용(기존 잡들과 같은 관용구).
- **was 서빙은 이 확장 범위 밖**: 브랜드 모니터링 was API(MON-BT 잔여 작업 세션)가
  `author_profile`을 서빙하게 되면 `image_object_path` 우선 + 원본 폴백(`TrackingItemAssembler`
  동형)을 그쪽 계약에 얹는다.

### 브랜드 본인 프로필 이미지 아카이브 (트랙 KK 확장, 2026-08-11 — brand_account)

`brand_account.profile_pic_url`(08-07 was 계약 필드로 추가)도 인스타 서명 CDN URL이라 며칠~2주면
만료된다 — 스윕이 매일 재조회해 저장값은 갱신되지만, 프론트가 이 URL을 직접 쓰면 등록 후 시간이
지난 브랜드부터 이미지가 깨진다(CLOSED 브랜드는 재조회도 멎어 영구히 깨짐). 게시자(author_profile)
확장과 완전히 같은 형태라 새 설계 없이 그대로 이식했다.

- **스키마**: `brand_account`에 동일한 3컬럼 — `image_object_path`, `image_source_name`,
  `image_archived_at`(`V20260811023454__brand_account_image_archive.sql`). 오염행 정정 UPDATE는
  불필요 — author_profile과 같은 근거(08-06 신설 테이블).
- **키 스킴**: `monitor-brand/<ig_user_id>.jpg` — ig_user_id 기준(author_profile과 동일 근거:
  username은 개명 가능). PK는 bigserial `id`라 UPDATE는 id 기준, 오브젝트 키만 ig_user_id.
- **잡·실행 시점**: `BrandProfileImageArchiveJob`(AuthorProfileImageArchiveJob과 동형 —
  `ImageStore`/`ImageDownloader`/`ParImageStore` 재사용, 같은 PAR·배치 상한 설정 공유).
  `BrandSweepJob.run()`의 finally에서 게시자 아카이브 직후 독립 실행 — 잡별 격리 래퍼
  (`runArchiveSafely`)라 한쪽 실패가 다른 쪽·스윕 결과에 영향을 주지 않는다.
- **was 서빙은 이 확장 범위 밖**: 브랜드 계정 API가 `image_object_path` 우선 + 원본 폴백을
  계약에 얹는 것은 후속(author_profile과 같은 위치).

### 브랜드 게시물 썸네일 아카이브 + was 서빙 계약 일괄 적용 (트랙 KK 확장, 2026-08-12)

08-12 운영 실측으로 두 층의 미완이 확인됐다: ① `brand_post_meta`(5,796행)·`brand_hashtag_post`는
아카이브 컬럼 자체가 없어 썸네일 서명이 만료되면 복구 수단이 없고, ② 아카이브 사본이 이미 쌓인
`author_profile`(1,323건)·`brand_account`(17건)조차 was가 `image_object_path`를 SELECT하지 않아
원본 서명 URL을 그대로 서빙했다(위 두 확장이 "was 서빙은 범위 밖·후속"으로 남겨둔 바로 그 자리).
프론트는 원본 URL을 `/img?u=` 프록시로 감싸는데, 프록시는 사본을 만들지 않으므로 서명 만료(`oe=`)와
함께 그대로 깨진다.

- **스키마**: `V20260812021500__brand_post_image_archive.sql` — 두 테이블에 동일 3컬럼 additive.
  오염행 정정 UPDATE 불필요(둘 다 08-06·08-11 신설 테이블).
- **잡 2개 신설**: `BrandPostThumbnailArchiveJob`(`monitor-brand-post/<short_code>.jpg`) ·
  `HashtagPostThumbnailArchiveJob`(`monitor-hashtag-post/<short_code>.jpg`). 해시태그 잡은
  **verdict='RELEVANT'만** 후보다 — 서빙(was findHashtagPosts)이 RELEVANT만 노출하고 판정은 저장 후
  불변(BrandHashtagCollectService — 기존 게시물 재판정 없음)이라 비노출분 아카이브는 스토리지 낭비.
  `(brand_id, short_code)` PK라 같은 게시물이 브랜드별 행으로 중복될 수 있어 후보는
  `DISTINCT ON (short_code)`(first_seen_at DESC — 최신 관측 URL), UPDATE는 short_code 기준 전 브랜드 행.
  `BrandSweepJob` finally에서 기존 두 프로필 아카이브 직후 독립 실행(잡별 격리 동형).
- **배치 상한(기존 잡과 의도적으로 다름)**: 기존 잡들은 후보 리스트를 상한에서 먼저 자르는데,
  "이미 아카이브됨" 스킵 행이 상한 창을 잠식해 창 밖의 미아카이브 꼬리에 영영 도달하지 못할 수 있다
  — 08-12 운영 실측: author_profile 미아카이브 2,675건이 상한 1,000/일에도 5일째 잔존(일별 아카이브
  191/0/279/530/323건 — 상한 근처에도 못 감). 신규 두 잡은 전 후보를 순회하되 **다운로드 시도
  (성공·실패)만 상한을 소모**한다(스킵 공짜) — 백로그(brand_post_meta 5,796 > 1,000)가 있어도 매 스윕
  상한만큼 확실히 전진, 첫 완주까지 ~6일. **기존 4개 잡(profile_meta·post_meta·author_profile·
  brand_account)의 동일 결함은 08-12 후속 PR에서 이 패턴으로 통일해 수정 완료** — 각 잡 테스트에
  "배치_상한은_다운로드_시도만_소모하고_스킵은_소모하지_않는다" 계약 테스트를 추가해
  (`BrandPostThumbnailArchiveJobTest` 동형) 창 잠식 결함의 재발을 막는다. 로그의 "잔여 N건 이월"
  의미도 "창 밖 전체 행 수" → "예산 소진으로 미룬 다운로드 필요 행 수"로 바뀌어 백로그 관측이
  정확해진다.
- **was 서빙 계약(위 확장들의 "후속" 완결)**: `BrandReadRepository` 4개 쿼리(findAccount·findPostMeta·
  findAuthors·findAuthorsByUsername·findHashtagPosts)에 `image_object_path` 추가, row record 4종에
  `imageObjectPath` 필드 추가. `BrandPostAssembler.resolveImageUrl`(아카이브 우선 + sanitize 폴백,
  `TrackingItemAssembler.resolveImageUrl` 동형)로 tagged 썸네일·게시자 프로필·hashtag 썸네일을,
  `BrandAccountAssembler.profile`이 브랜드 프로필을 각각 전환. **해시태그 게시자 프로필 사진만 원본
  그대로** — 프로필 보강 자체가 스펙 §5 보류라 아카이브할 산지(author_profile 행)가 없다. 이 값도
  열거 시점 서명 URL이라 만료되면 깨진다 — 보강 도입 시 함께 해소될 알려진 잔여.

## 검증

- `./gradlew :monitoring:test` — 결함①·② + 게시물 썸네일 확장 포함 전체 통과(개별 수치는 PR 본문 참고).
- `./gradlew :was:test` — 전체 통과(개별 수치는 PR 본문 참고).
- monitoring: fake `ImageStore`/`ImageDownloader`로 신규 아카이브·source_name 미변경 시 스킵·건
  단위 실패 격리·PAR 미설정 no-op·`ProfileMetaRepository`/`PostMetaRepository` 스킴 정규화(무효값 →
  null, 기존 유효값 보존)를 검증(`PostThumbnailArchiveJobTest`가 `ProfileImageArchiveJobTest`와 동형).
- was: `TrackingItemAssembler`가 profile_meta·post_meta 양쪽 모두 `image_object_path` 있으면
  `/img/...` 서빙, 없으면 원본 CDN 폴백, 무효 스킴이면 null임을 검증.

## 관련 문서

- [specs/2026-07-31-monitoring-profile-image-design.md](../superpowers/specs/2026-07-31-monitoring-profile-image-design.md) — 설계 전문.
- [monitoring-was-contract.md](../contracts/monitoring-was-contract.md) profile_meta 절(v2.4) —
  `image_object_path`·`image_source_name`·`image_archived_at` 계약, `profileImageUrl` 응답이
  절대 URL/상대 `/img/` 경로 둘 다일 수 있다는 was 계약 변화.
- [II-POST-등록-프로필-메타.md](II-POST-등록-프로필-메타.md) — 이 트랙 착수 전 "monitoring의
  profile_meta는 analytics 이미지 아카이브 대상이 아니다"라고 남긴 서술의 후속 정정.
- [J-서빙-이미지-아카이브.md](J-서빙-이미지-아카이브.md) — 버킷만 공유하는 analytics 쪽 원형 잡.
