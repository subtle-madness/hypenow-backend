# monitoring ↔ was 계약 — was 개발자용

> **living 문서** — monitoring 모듈이 was에 제공하는 계약의 정본. 구현과 함께 갱신한다.
> 배경·설계 근거는 [specs/2026-07-28-monitoring-module-design.md](../superpowers/specs/2026-07-28-monitoring-module-design.md)(v1) +
> [specs/2026-07-30-monitoring-alarm-module-design.md](../superpowers/specs/archive/2026-07-30-monitoring-alarm-module-design.md)(v2 — 알람 소유 이동·승인 폐지) 참조.
> P2 표면(댓글·계정 메타·매칭 키워드·share 해소)의 확장 요구 근거는
> [monitoring-v3-extension-request.md](monitoring-v3-extension-request.md) P2.
> 상태: **v2.8 (브랜드 해시태그 감지 확장 — 2026-08-11, 08-12 API 형태 정정)** · 명령 API **3종**(등록·연장·해지) +
> share 해소 1종·조회 표면(테이블 8 + 알람 대장 + 뷰 2)·알람은 **monitoring 소유**(was는 알람 경로에서 빠짐)·
> 에러 어휘 전부 구현과 일치. **v2.8은 별도 서브시스템**(브랜드 태그 모니터링 — target/캠페인
> 계약과 무관한 신규 3테이블, §8)이라 위 "테이블 8 + 알람 대장 + 뷰 2" 집계에는 포함하지 않는다.
> 이력: v1.0 (2026-07-29, 승인·기각 명령 2종 + was 09:00 이메일 크론) → **v1.1**(2026-07-30, P2 표면 —
> post_comment·profile_meta·matched_keywords·share 해소, `feat/monitoring-v3-p2`) → **v2.0**(2026-07-30,
> 알람 소유 이동·승인 폐지·`target.user_id`·알람 이벤트 대장, `feat/monitoring-alarm-module`) — **v1.1과 v2.0은
> 공통 조상에서 병렬 개발**(서로 다른 파일을 확장해 파일 충돌 없이 진행), 이 머지로 **v2.1**로 통합 →
> **v2.2**(2026-07-30, P1 확장 4종 — `post_meta`·hidden/error 상태 신호(`target.tracked_hidden_at`·
> `target.fetch_failing`)·`sweep_run`·`target.matched_keywords` 산지 이설) →
> **v2.3**(2026-07-30, 같은 유저 이중 추적 배제 — 감지가 같은 `user_id`의 다른 활성 target이 이미
> 추적 중인 shortcode를 후보에서 뺀다. 프론트 계약 §6.25 요구, `feat/monitoring-duplicate-tracking-exclusion`)
> → **v2.4**(2026-07-31, 프로필 이미지 아카이브 — `profile_meta`에 `image_object_path`·
> `image_source_name`·`image_archived_at` 추가, was는 아카이브본을 우선 서빙,
> `feat/monitoring-profile-image-archive`) → **v2.5**(2026-08-01, 게시물 썸네일 아카이브(트랙 KK
> 확장, v2.4와 동형) — `post_meta`에도 `image_object_path`·`image_source_name`·`image_archived_at`
> 추가, was는 `post.thumbnailUrl`도 아카이브본을 우선 서빙, `fix/monitoring-post-thumbnail-archive`)
> → **v2.6**(2026-08-03, 좋아요 숨김 관측 플래그 — `post_snapshot.likes_hidden` 추가, was는
> Snapshot 응답에 `likesHidden`으로 노출해 FE가 "숨김"과 "수집 실패"를 구분 표시,
> `feat/likes-hidden-flag`)
> → **v2.7**(2026-08-05, 공유 숨김 관측 플래그 — `post_snapshot.shares_hidden` 추가(v2.6과
> 동형), was는 Snapshot 응답에 `sharesHidden`으로 노출. 게시자가 "공유 횟수 숨기기"
> (`share_count_disabled`)를 켜거나 좋아요 숨김(`like_and_view_counts_disabled` — IG 앱 문구대로
> 공유 노출도 함께 끈다)을 켜면 공유 키가 영구 부재해 shares가 null로 남는데, FE는 이 플래그로
> "비공개"와 "수집 실패"를 구분 표시. FEED는 공유 자체가 미지원(null 강제)이라 플래그도 false로
> 접는다, `feature/reels-retry-logic-missing-content-39b848`)
> → **v2.8**(2026-08-11, 브랜드 태그 모니터링에 해시태그 발견 게시물 추가 — 브랜드 계정 태그·
> 계정명·정규화 변형 해시태그를 매일 열거해 Gemini로 브랜드 관련성 판정, 통과분을 was가 조회.
> 신규 `GET/PUT /v1/brand-monitoring/accounts/{accountId}/hashtag-exclusions`(자사 태그 오탐
> 방지 문자열 관리) — was 신규 API·상세는 §8, `feat/brand-hashtag-detection`.
> **08-12 정정(같은 버전 내)**: 발견 게시물은 처음엔 `BrandPostResponse.source: "hashtag"`로
> 기존 §6-1 게시물 목록에 합류시켰으나, 스냅샷·댓글·팔로워 보강이 없는 별개 성격의 데이터를
> 같은 필터·정렬·counts 계약에 끼워 맞추면 null 필드만 늘어난다는 FE 판단으로 **전용 API**
> `GET /v1/brand-monitoring/accounts/{accountId}/hashtag-posts`(슬림 `BrandHashtagPostResponse`)로
> 분리했다 — `BrandPostResponse.source`는 `"tagged"`/`"direct"` 2종으로 되돌아갔다,
> `feat/brand-hashtag-separate-api`).
> 이후 변경은 이 문서를 먼저 갱신한 뒤 코드에 반영한다.

## 0. 한 장 요약

- **쓰기(명령)는 전부 monitoring 내부 API — 등록·연장·해지 3개.** 승인·기각은 v2에서
  폐지(감지 즉시 자동 추적). was는 monitoring DB에 어떤 쓰기도 할 수 없다(읽기 전용 계정).
- **읽기(조회)는 전부 monitoring DB `public` 스키마 SELECT** — 조회용 API는 없다.
  목록·상태·후보·추이·알람 이벤트 조회 모두 SELECT.
- **target = 캠페인(등록) 1건** — 같은 인플루언서를 여러 캠페인이 각자 키워드로 등록
  가능. 스냅샷은 계정·게시물 단위라 캠페인 사이에 공유된다(target → username /
  tracked_short_code로 조인해서 본다).
- was가 자기 `app` 스키마에 보관할 것: `(user_id, target_id, registration_key)` 매핑.
  target_id는 **논리 참조**(FK·크로스 DB 조인 금지 — `saved_influencers.handle` 관용구와 동일).
- **알람은 monitoring 소유** — 이벤트 대장 `alarm_event`가 단일 원천이고, 메일 발송도 monitoring
  크론이 한다. was는 앱 내 알림·히스토리를 이 테이블에서 **읽기만** 한다.

## 1. 접속 정보

| 항목 | 값 |
|---|---|
| 명령 API | `http://monitoring:8083` — **전용 도커 네트워크 `monitoring-net`** 경유(was 컨테이너가 이 네트워크에 소속돼야 이름이 해석됨). 호스트 포트·Caddy 미노출 |
| 인증 | **없음 — 네트워크 소속이 곧 인증.** `monitoring-net`에는 was와 monitoring만 소속. 헤더·토큰 불필요 |
| test(스테이징) 환경 | `http://test-monitoring:8083` — `test-monitoring-net`(test-was와 둘만 소속). 운영 monitoring은 test에서 DNS 해석 자체가 안 됨(오배선 fail-closed) |
| 조회 DB | `postgres` 인스턴스의 `monitoring` DB, 읽기 전용 계정(`public` 스키마만 GRANT). test는 test-postgres의 monitoring DB |
| 타임아웃 권고 | 등록 POST 10s (동기 Hiker 수집 포함) / 나머지 명령(연장·해지) 5s |

## 2. 명령 API

공통 에러 응답:

```json
{ "code": "SUBJECT_NOT_FOUND", "message": "계정을 찾을 수 없음: @foo" }
```

| code | HTTP | 의미 |
|---|---|---|
| `VALIDATION` | 400 | 요청 형식·필수 필드 위반 |
| `TARGET_NOT_FOUND` | 404 | 해당 id 없음 |
| `SUBJECT_NOT_FOUND` | 404 | 인스타에 계정/게시물이 없음 (등록 시) |
| `PRIVATE_ACCOUNT` | 422 | 비공개 계정이라 수집 불가 |
| `INVALID_STATE` | 409 | 상태상 불가한 명령 (예: 종결된 target 연장) |
| `FETCH_FAILED` | 502 | Hiker 일시 오류 — was는 그대로 프론트에 실패 전달, 재시도는 사용자 몫 |

(인증 에러 없음 — 접근 통제는 네트워크 소속으로 강제되므로, 연결 자체가 안 되면 배선 문제다.)

위 표 밖으로 나갈 수 있는 응답은 두 가지뿐이다: 예기치 못한 서버 오류
`{"code":"INTERNAL"}` 500(§4대로 재시도 가능), 그리고 계약 밖 경로·메서드로 보냈을 때의
프레임워크 상태 보존 응답(`{"code":"NOT_FOUND"}` 404 등 — 이건 오배선 신호다).

### 2-1. 등록 — `POST /api/targets`

```json
// 계정 등록 (키워드 감시 캠페인)
{
  "registrationKey": "was가 생성한 UUID",   // 멱등 키 — 재시도 시 같은 target 반환
  "userId": 12345,   // was 유저 id — 알람 수신자. 필수(누락 시 VALIDATION 400)
  "type": "ACCOUNT",
  "username": "some_influencer",
  "keywordRule": {
    "and":     ["샤넬", "립스틱"],   // 전부 포함돼야 매칭 (0개 이상)
    "any":     ["chanel", "샤넬"],   // 하나 이상 포함돼야 매칭 (0개 이상)
    "exclude": ["이벤트", "공구"]    // 하나라도 포함되면 배제 (0개 이상)
  },                                 // and·any 중 최소 한 목록은 비어 있지 않아야 (VALIDATION)
  "expiresAt": "2026-08-28T23:59:59+09:00"
}

// 게시물 등록 (단건 추적)
{
  "registrationKey": "…",
  "userId": 12345,   // was 유저 id — 알람 수신자. 필수(누락 시 VALIDATION 400)
  "type": "POST",
  "shortCode": "DAbCdEfGhIj",
  "expiresAt": "2026-08-28T23:59:59+09:00"
}
```

동작: 검증 → **동기로 첫 Hiker 수집**(계정 존재 확인 + 첫 스냅샷 적재) → 응답.
같은 `registrationKey` 재호출은 새로 만들지 않고 기존 target을 200으로 반환(크래시
복구용). 키가 다르면 같은 계정·키워드라도 별도 캠페인이 생긴다.

**ACCOUNT 등록의 status는 `WATCHING`이고, 첫 키워드 감지 시 monitoring이 스스로 `TRACKING`으로
전환한다**(승인 절차 없음 — v2). 전환 시점은 일일 스윕(KST 02:00)이다.

**replay(200) 응답의 `firstSnapshot`은 `null`이다** — 재시도마다 Hiker를 다시 부르면
콜 과금이 배로 늘어서 재수집을 하지 않는다. 첫 수집분은 이미 스냅샷 테이블에 있으니
필요하면 §3 조회 표면에서 SELECT로 읽는다.

```json
// 201 Created (재시도 replay는 200)
{
  "targetId": 17,
  "status": "WATCHING",            // POST 등록이면 바로 "TRACKING"
  "firstSnapshot": {
    "profile": { "followers": 12345, "following": 321, "mediaCount": 87 },
    "recentPostCount": 12          // POST 등록이면 profile 대신 post 지표
  }
}
```

### 2-2. 기간 연장 — `PATCH /api/targets/{id}`

```json
{ "expiresAt": "2026-09-30T23:59:59+09:00" }   // 200 { "targetId": 17, "expiresAt": "…" }
```

### 2-3. 해지 — `DELETE /api/targets/{id}`

상태를 CANCELED로 전이(행·스냅샷 보존 — 물리 삭제 아님). 멱등: 이미 종결이면 현재
상태 그대로 200. `// 200 { "targetId": 17, "status": "CANCELED" }`

### 2-6. 공유 단축 링크 해소 — `POST /api/share/resolve` (v1.1)

`instagram.com/share/…` 토큰은 shortcode가 아니라서 해소가 필요하다. was에는 인스타
접속 수단이 없으므로 monitoring이 Hiker(`/v2/media/info/by/url`)로 해소해 준다.
**등록과 분리된 전처리 API다** — was가 등록 전에 이걸 호출해 shortcode를 얻고, 등록은
기존 2-1 그대로 진행한다(확장 요구 P2-8의 "등록 API에 shareUrl 통합" 제안과 다른 형태 —
등록 플로우 무변경으로 채택. 타임아웃 권고 10s).

```json
// 요청
{ "url": "https://www.instagram.com/share/reel/AbCdEfG/" }
// 200 — 해소 성공
{ "shortCode": "DbV7LgZsKG8", "username": "rarebeauty", "contentType": "REELS" }
```

| code | HTTP | 의미 |
|---|---|---|
| `SHARE_LINK_UNRESOLVED` | 422 | URL 형식 불량·해소 불가(Hiker 400 등) — 유저에게 "링크를 확인해 주세요" |
| `SUBJECT_NOT_FOUND` | 404 | 해소는 됐으나 게시물이 삭제·비공개(Hiker 404) |
| `FETCH_FAILED` | 502 | Hiker 일시 오류 — 재시도는 사용자 몫 |

- 일반 게시물 URL(`/p/`·`/reel/`·`/reels/`)을 넣어도 동작한다(shortcode를 그대로 확인 반환).
- ⚠️ **실제 share 토큰 URL 실측은 잔여**(샘플 확보 불가로 일반 게시물 URL만 실측 —
  2026-07-30). share 토큰에서 해소 실패율이 높으면 Hiker 다른 엔드포인트로 보강한다.

## 3. 조회 표면 (`public` 스키마 — 읽기 전용 SELECT)

아래 테이블·뷰가 계약이다. 여기 없는 객체는 내부 구현이므로 조회하지 말 것
(monitoring DB 안에서의 조인은 자유. 단 app 스키마·분석 결과와의 크로스 DB 조인은
기존 규칙대로 금지 — 조합은 was 코드에서).

### target — 캠페인 (등록당 1행)

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `id` | bigint PK | target_id — was가 매핑에 보관하는 그 값 |
| `user_id` | bigint null | 소유 유저(was 유저 논리 참조 — 알람 수신자). V3 이전 등록분은 null |
| `type` | text | `ACCOUNT` / `POST` |
| `username` | text | 계정 핸들 (POST 등록도 소유 계정 기록) |
| `short_code` | text null | POST 등록 시의 게시물 |
| `keyword_rule` | jsonb | 키워드 규칙 `{"and":[…],"any":[…],"exclude":[…]}` (ACCOUNT 전용). 매칭 = and 전부 ∧ (any 비었거나 하나 이상) ∧ exclude 전무 — 부분 문자열·대소문자 무시·캡션 전문 |
| `status` | text | `WATCHING` / `TRACKING` / `EXPIRED` / `CANCELED` / `FAILED` |
| `tracked_short_code` | text null | **첫 감지 자동 전환**(또는 직접 등록)된 추적 게시물 |
| `tracked_since` | timestamptz null | TRACKING 전환 시각 |
| `registration_key` | text unique | was가 넘긴 멱등 키 |
| `expires_at` | timestamptz | 만료 시각 (PATCH로 연장 가능) |
| `registered_at` / `closed_at` | timestamptz | 등록 / 종결(EXPIRED·CANCELED·FAILED) 시각 |
| `last_fetched_at` | timestamptz null | 마지막 수집 시각 |
| `fail_reason` | text null | FAILED 사유 (`SUBJECT_NOT_FOUND` 등 §2 어휘) |
| `tracked_hidden_at` | timestamptz null | **(v2.2)** 추적 대상 접근 불가(게시물 삭제·비공개, 계정 삭제·비공개 전환) 감지 시각. 스윕이 결정적 수집 불가(`SUBJECT_NOT_FOUND`·`PRIVATE_ACCOUNT`)를 만나면 세팅하고 **status는 유지**(재스윕 계속) — 재공개가 감지되면(수집 성공) null로 복귀. 프론트 hidden 상태의 산지(TRACKING+값 → hidden, 만료 후에도 값 유지 → "만료 후 hidden 유지") |
| `fetch_failing` | boolean NOT NULL default false | **(v2.2)** 일시 수집 오류(5xx·타임아웃)가 당일 재시도 라운드 소진까지 해소되지 않은 target 표시. 수집 성공 시 false로 복귀. 프론트 error 상태의 산지 |
| `matched_keywords` | jsonb null | **(v2.2)** 감지 자동 전환 시점에 실제 매칭된 키워드 배열(and 전부 + any 중 캡션에 실제 존재한 것, 등록 원문·순서 유지). POST 직접 등록·감지 전 WATCHING은 null — was는 null이면 빈 배열로 폴백(프론트 계약 "url 등록이면 빈 배열"과 정합). 매칭 판정 정본은 monitoring |

**status 의미 개정 (v2.2)** — v2.2부터 스윕은 target을 FAILED로 종결하지 않는다. 결정적 수집
불가는 `tracked_hidden_at`, 재시도 소진 일시 오류는 `fetch_failing`으로 신호하고 상태를 유지한다
(재공개·복구 시 자동 복귀 — 프론트 상태 머신의 "hidden 재공개 복귀·error 복구 복귀"와 정합).
FAILED는 **등록 시점 실패 전용**으로 축소되는데, 등록은 동기 수집 성공 후에만 행을 만들므로 현
구조에서 신규 FAILED는 사실상 발생하지 않는다(기존 행은 이력으로 잔존). EXPIRED(기간 만료)는
기존대로.

### detected_candidate — 감지 후보 (캠페인 소속)

> **⚠ deprecated (v2)** — 신규 적재가 중단됐다(승인 플로우 폐지). 테이블과 기존 행은 이력으로 남지만
> 새 행은 생기지 않으므로 조회하지 말 것. DROP은 참조가 끊긴 다음 릴리스의 contract 단계.
> **v2.2**: `matched_keywords`의 산지가 `target.matched_keywords`로 이설됐다 — 승인 폐지로 이
> 테이블에 새 행이 안 생겨 v1.1 시점에 도입한 `matched_keywords`는 사실상 무산됐던 것을 정정한다
> (아래 행은 이력 참고용).

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `id` | bigint PK | candidate_id (v1의 승인/거절 명령에 쓰였음 — v2는 명령 삭제, 이력값만 남음) |
| `target_id` | bigint | 소속 캠페인 |
| `short_code` | text | 감지된 게시물 |
| `detected_at` | timestamptz | 감지 시각 (02:00 배치) |
| `caption_excerpt` | text | 키워드 주변 캡션 발췌 (FE 노출용) |
| `status` | text | `PENDING` / `APPROVED` / `REJECTED` |
| `matched_keywords` | jsonb null | **(v1.1 · deprecated)** 매칭된 키워드 배열(등록 키워드 원문 그대로 — and 전부 + any 중 캡션에 실제 존재한 것). v1.1 이전 감지분은 null. **v2.2부터 산지는 `target.matched_keywords`** — 이 컬럼은 이력값만 남고 새로 채워지지 않는다(승인 폐지 이후 이 테이블에 신규 행 자체가 없음) |

같은 (target_id, short_code)는 한 번만 생성 — 거절해도 재감지로 되살아나지 않는다.
**등록 시각 이후에 게시된 게시물만 감지 대상** — 캠페인 등록 전의 옛 키워드 게시물은
후보로 오르지 않는다(게시 시각 ≥ target.registered_at).

### alarm_event — 알람 이벤트 대장 (앱 내 알림·히스토리의 단일 원천)

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `id` | bigint PK | 이벤트 id. 워터마크 대신 이 id와 상태로 발송을 관리한다 |
| `target_id` | bigint | 소속 캠페인 (논리 참조) |
| `user_id` | bigint | 수신자 (was 유저 논리 참조) |
| `event_type` | text | `COLLECTION_STARTED` / `COLLECTION_ENDED` / `METRICS_HIDDEN` / `CONTENT_UNAVAILABLE` |
| `payload` | jsonb | 문안 재료 — `username`, `shortCode`, METRICS_HIDDEN의 `metrics[]`, CONTENT_UNAVAILABLE의 `failReason` |
| `occurred_at` | timestamptz | 발생 시각 |
| `dispatch_after` | timestamptz | 메일 발송 레인(즉시 = occurred_at, 아침 = 적재 당일 09:00 KST) |
| `email_status` | text | `PENDING`/`SENT`/`SKIPPED_OPTOUT`/`SKIPPED_NO_RECIPIENT`/`FAILED` |
| `email_sent_at` | timestamptz null | SENT일 때만 채워진다 |

- **메일 발송은 monitoring 몫이다** — was는 이 테이블을 읽어 앱 내 알림·히스토리를 서빙만 한다
  (`email_status`가 SKIPPED_OPTOUT이어도 앱 내에서는 보여준다 — 옵트아웃은 메일만 끈다).
- 읽음 상태는 was가 자기 `app` 스키마에 워터마크로 보관한다(프론트 API 작업 때).
- 화면 문구: 수집 시작 / 수집 종료 / 일부 지표 비공개 / 콘텐츠 비공개·삭제·수집 오류.
- 실 스키마에는 `email_attempts`(발송 시도 횟수) 컬럼도 있지만 **계약 밖**(발송 크론 내부
  재시도 상한용) — was는 읽지 말 것.
- **CONTENT_UNAVAILABLE 발화 시점(v2.2)**: 전이 시점에만 1회 발화한다 — `target.tracked_hidden_at`이
  null→값으로 전이될 때(`payload.failReason` = `SUBJECT_NOT_FOUND` | `PRIVATE_ACCOUNT`),
  `target.fetch_failing`이 false→true로 전이될 때(`failReason` = `FETCH_FAILED`). 이미
  hidden/failing인 target의 반복 실패는 재발화하지 않는다. 복귀(재공개·복구)는 이벤트가 아니다.

### profile_snapshot / post_snapshot — 관측치 (계정·게시물 단위, 캠페인 간 공유)

```
profile_snapshot(username, captured_on date, followers, following, media_count)
                 PK (username, captured_on) — 일 1회 upsert. 컬럼은 이 5개가 전부다

post_snapshot(username, short_code, captured_on date, content_type REELS|FEED,
              likes, likes_hidden boolean NOT NULL default false,
              comments, views, saves,
              shares, shares_hidden boolean NOT NULL default false, reposts)
              PK (short_code, captured_on) — 일 1회 upsert
```

- 지표 6종: 좋아요·댓글·조회·저장·공유·리포스트. **취득 불가 지표는 null**
  (예: 피드 조회수 — 항상 null. Hiker 필드 매핑의 정본은
  [plans/2026-07-28-monitoring-hiker-findings.md](../superpowers/plans/2026-07-28-monitoring-hiker-findings.md)).
- `likes_hidden` **(v2.6)**: 게시자의 좋아요 수 숨김(`like_and_view_counts_disabled`) 관측.
  숨김이면 like_count가 실측이 아니라 프리뷰 잔여값으로 잘려 와(운영 실측 08-03: 서로 다른
  두 게시물이 똑같이 3) likes를 null로 저장하는데, was·FE가 "숨김"(행 있음 + true)과
  "그날 수집 실패"(행 부재)를 구분 표시하는 유일한 신호가 이 플래그다. 해제 관측 시 false 복귀.
- `shares_hidden` **(v2.7)**: 게시자의 공유 횟수 숨김 관측 — `share_count_disabled` 토글이거나
  좋아요 숨김 커플링(IG 앱 문구 "좋아요 수 및 공유 횟수는 회원님만", 08-05 실측: lvcd=true
  게시물 전원 공유 키 영구 부재). 숨김이면 reshare_count 키가 아예 안 와 shares가 null로
  남는다 — 구분 표시 계약은 likes_hidden과 동일. 해제 관측 시 false 복귀. 숨김 게시물은
  monitoring의 저장·공유·리포스트 재시도 판정에서도 공유 항이 제외된다(헛 콜 방지).
- 캠페인 추이는 target을 조인해 본다: `target.username` → profile_snapshot,
  `target.tracked_short_code` → post_snapshot.

### post_comment — 추적 게시물 댓글 (v1.1 · 게시물 단위, 캠페인 간 공유)

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `short_code` + `id` | text, PK 복합 | 게시물 × 인스타 댓글 ID |
| `author` | text | **원본 핸들** — 마스킹은 was 응답 생성 단계 책임(프론트 계약 6.25 PostComment) |
| `body` | text | 댓글 본문 |
| `like_count` | bigint | 댓글 좋아요 수 |
| `commented_at` | timestamptz | 작성 시각 |
| `owner_reply_text` | text null | **게시물 작성자 본인 답글**(첫 건의 본문). 제3자 답글은 수집 안 함 |

- **일일 스윕 동승, 게시물당 전량 교체 갱신**(추적 게시물당 Hiker 1콜 —
  `monitoring.comment-pages` 기본 1페이지 = **15건 상한**. 확장 요구의 "20건 제안"과
  다른 이유: Hiker 페이지가 15건 단위라 20건은 2콜이다. 프론트 표시 8건을 충분히 커버).
- ⚠️ **수집 모수는 IG 기본(랭킹) 정렬 상위 15건**이다 — "전체 중 최신 15건" 보장이
  아니다. was는 `commented_at` 내림차순으로 정렬해 응답한다(프론트 계약의 "최신순"은
  이 수집 모수 안에서의 정렬).
- 본문·좋아요 등 필드 결손 댓글은 **저장하지 않는다**(프론트에 부분 결손 렌더 경로 없음).
- 답글은 댓글 응답에 동봉되는 미리보기(`preview_child_comments`) 범위에서만 판정한다 —
  미리보기 밖 답글은 놓칠 수 있다(보수 수집).

### profile_meta — 계정 표시 메타 (v1.1 · 계정 단위 최신 1행, 캠페인 간 공유)

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `username` | text PK | 계정 핸들 |
| `display_name` | text null | 계정 풀네임 (Hiker `full_name`) |
| `profile_image_url` | text null | 원본 CDN 프로필 이미지 URL — **아카이브본이 있으면 was가 이 컬럼 대신 `image_object_path`를 우선 서빙하므로 인스타 CDN 서명 만료 노출은 줄었다. 다만 아카이브 전이거나 `MONITORING_IMAGE_PAR_URL` 미설정 환경에서는 여전히 이 원본 CDN URL이 그대로 나가므로 만료(~4일, `oe=` 서명) 주의는 계속 유효하다**(프론트 계약 4절 35번) |
| `last_uploaded_at` | date null | 계정의 최근 게시일(KST) — 열거된 게시물 `taken_at` 최댓값 |
| `updated_at` | timestamptz | 마지막 갱신 시각 |
| `image_object_path` | text null | **(v2.4)** monitoring이 자체 아카이브한 OCI 오브젝트 경로(`monitor-profile/<username>.jpg`). null이면 아직 아카이브 전이거나 PAR 미설정 — was가 원본 CDN URL로 폴백 |
| `image_source_name` | text null | **(v2.4)** 마지막 아카이브 시점 원본 URL 파일명(쿼리스트링 제외) — 재다운로드 판정용 내부 컬럼, was는 읽지 않는다 |
| `image_archived_at` | timestamptz null | **(v2.4)** 마지막 아카이브 성공 시각. was는 읽지 않는다 |

- 계정 수집(등록 동기 수집·일일 스윕)마다 upsert — 스냅샷과 달리 이력 없이 최신 1행.
- **POST 등록만 있는 계정도 07-30(트랙 II)부터 `display_name`·`profile_image_url`은 채워진다** —
  단건 응답(`/v2/media/by/code`)의 `user.full_name`·`user.profile_pic_url`을 게시물 수집(등록 동기
  수집·일일 스윕 단건 보강) 경로에서 제로 콜로 파싱해 upsert(Hiker 콜 추가 없음). **다만
  `last_uploaded_at`은 이 경로로 채울 수 없어 POST 전용 계정에서는 계속 null로 남는다** —
  단건 응답은 게시물 1건의 게시일만 알 뿐 계정 열거 전체의 최댓값(계정 갈래의 정의)을 알 수 없다.
- **`followers`는 07-31(트랙 II 후속)부터 POST 전용 계정에서도 채워지되, "최초 1회만" 수집되고
  이후 갱신되지 않는다** — `DailySweepJob`이 `profile_snapshot` 행이 아직 없는 계정에 한해
  프로필을 1콜(열거 없음) 조회해 채운다. was가 서빙하는 `followers`는 시계열이 아니라 최신 1행
  단일값이라 매일 갱신할 실익이 없어 의도적으로 최초 수집 시점 값에 고정한다(계정당 평생 약
  1콜). 이 조회는 best-effort라 실패해도 캠페인 생존 판정에 영향이 없고, 실패한 계정은 다음
  스윕에서 (여전히 행이 없으므로) 다시 시도된다.
  was는 세 필드(`display_name`·`profile_image_url`·`followers`) 모두, 그리고 `last_uploaded_at`도
  여전히 null 가능성을 전제해야 한다(프론트 계약상 nullable 유지).

**⚠ v2.4 계약 변화 — `profileImageUrl` 응답 값의 형태가 둘로 갈린다.** was가 프론트에 내려주는
`profileImageUrl`은 이제 **절대 URL(원본 CDN, `https://...`)일 수도, 상대 경로(`/img/monitor-profile/<username>.jpg`)일 수도 있다** —
`image_object_path`가 있으면 후자, 없으면 전자(§ 위 `profile_image_url` 행 참고). `/img/`는 **was
엔드포인트가 아니라 celfit-front의 Vercel rewrite**(`/img/:path*` → OCI 공개 버킷 글롭)라서 이
상대 경로는 **프론트에서만 해석**된다 — was가 자체적으로 `/img/`를 라우팅하거나 리다이렉트할
필요는 없다. 이 관용구는 was v1 발굴/상세/저장 목록이 analytics 이미지 아카이브(트랙 J)에 이미
쓰고 있는 것과 동일하다 — 신규 패턴이 아니다.

### post_meta — 추적 게시물 표시 메타 (v2.5 · 게시물 단위 최신 1행, 캠페인 간 공유)

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `short_code` | text PK | 게시물 |
| `username` | text NOT NULL | 게시물 소유 계정 |
| `content_type` | text | `REELS` / `FEED`. **캐러셀(sidecar)은 FEED로 접는다**(Hiker `product_type='carousel_container'` — 피드 조회수 null 규약과 일치, 실측 2026-07-30) |
| `uploaded_at` | date NOT NULL | 게시일(`taken_at`의 KST 날짜) |
| `caption` | text NOT NULL | 캡션 원문 전문(개행 유지). 캡션 없는 게시물은 빈 문자열(프론트 계약 caption null 불가) |
| `thumbnail_url` | text null | 원본 CDN 썸네일 URL(Hiker `image_versions2` 첫 후보). 스윕마다 갱신 — **아카이브본이 있으면 was가 이 컬럼 대신 `image_object_path`를 우선 서빙하므로 인스타 CDN 서명 만료 노출은 줄었다. 다만 아카이브 전이거나 `MONITORING_IMAGE_PAR_URL` 미설정 환경에서는 여전히 이 원본 CDN URL이 그대로 나가므로 만료(~4일, `oe=` 서명) 주의는 계속 유효하다** |
| `first_seen_at` | timestamptz NOT NULL | 최초 관측 시각(upsert에도 보존) |
| `image_object_path` | text null | **(v2.5)** monitoring이 자체 아카이브한 OCI 오브젝트 경로(`monitor-post/<short_code>.jpg`, 트랙 KK 확장 — profile_meta와 동형). null이면 아직 아카이브 전이거나 PAR 미설정 — was가 원본 CDN URL로 폴백 |
| `image_source_name` | text null | **(v2.5)** 마지막 아카이브 시점 원본 URL 파일명(쿼리스트링 제외) — 재다운로드 판정용 내부 컬럼, was는 읽지 않는다 |
| `image_archived_at` | timestamptz null | **(v2.5)** 마지막 아카이브 성공 시각. was는 읽지 않는다 |

- 수집이 게시물을 지나는 모든 경로(등록 동기 수집·스윕 열거·단건 보강)에서 upsert된다.
- `taken_at`을 못 얻은 게시물은 upsert하지 않는다(잘못된 게시일을 만들지 않음 — 기존 행 보존).
- `post_snapshot`이 있는 게시물은 `post_meta`도 있다고 봐도 된다(같은 경로에서 적재).
- **⚠ v2.5 계약 변화 — `post.thumbnailUrl` 응답 값의 형태가 둘로 갈린다.** `profileImageUrl`(v2.4)과
  동일 관용구 — `image_object_path`가 있으면 상대 경로 `/img/monitor-post/<short_code>.jpg`, 없으면
  원본 CDN 절대 URL이다. `/img/`는 was 엔드포인트가 아니라 celfit-front의 Vercel rewrite라서
  프론트에서만 해석된다.

### sweep_run — 일일 스윕 실행 대장 (v2.2 · 1실행 1행)

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `id` | bigserial PK | 실행 id |
| `started_at` | timestamptz NOT NULL | 시작 시각 |
| `completed_at` | timestamptz null | 완료 시각 |
| `ok` | boolean null | 정상 완주 여부 |

- was는 `max(completed_at) WHERE ok`로 6.26 `meta.lastCollectedAt`(마지막 성공 배치 완료 시각)을 읽는다.
- `ok=true`는 스윕 루프 정상 완주(계정 단위 격리 실패 포함 — 부분 실패는 `target.fetch_failing`으로
  표현), 크래시·중단된 실행은 `ok`가 true가 되지 않아 워터마크에서 자연 제외된다.

### 조회 뷰 (v1.0 구현 확정 — v2.2에서 target 구획 3컬럼 추가)

#### `v_target_overview` — 캠페인 목록 (target 1행당 1행, 29컬럼)

캠페인 목록 화면은 이 뷰 하나로 서빙 가능하게 유지한다. 컬럼:

| 구획 | 컬럼 |
|---|---|
| target (18) | `target_id`(= target.id), `user_id`, `type`, `username`, `short_code`, `keyword_rule`, `status`, `tracked_short_code`, `tracked_since`, `registration_key`, `expires_at`, `registered_at`, `closed_at`, `last_fetched_at`, `fail_reason`, `tracked_hidden_at`, `fetch_failing`, `matched_keywords` |
| 최신 프로필 스냅샷 (3) | `profile_captured_on`, `followers`, `media_count` |
| 최신 게시물 스냅샷 (8) | `post_captured_on`, `content_type`, `likes`, `comments`, `views`, `saves`, `shares`, `reposts` |

- 스냅샷 구획은 **각각 최신 1행**(captured_on DESC LIMIT 1)이고, 프로필과 게시물의
  `captured_on`은 서로 다를 수 있어 별도 컬럼(`profile_captured_on` / `post_captured_on`)이다.
- 추적 게시물이 없는 캠페인(WATCHING)은 **게시물 구획 8컬럼이 전부 null**.
  아직 프로필 수집 전이면 프로필 구획 3컬럼도 null (LEFT JOIN — target 행 자체는 항상 나온다).
- `followers`/`media_count`만 노출한다(스냅샷의 `following`은 뷰에 없음 — 필요하면
  `profile_snapshot`을 직접 조회).

#### `v_target_timeseries` — 추적 게시물 일별 추이 (target_id × captured_on)

| 구획 | 컬럼 |
|---|---|
| 키 (3) | `target_id`, `captured_on`, `content_type` |
| 지표 6종 | `likes`, `comments`, `views`, `saves`, `shares`, `reposts` |
| 전일 대비 증감 6종 | `likes_delta`, `comments_delta`, `views_delta`, `saves_delta`, `shares_delta`, `reposts_delta` |

- 추적 게시물이 있는 캠페인만 행이 나온다(INNER JOIN — WATCHING 캠페인은 0행).
- 첫날 행의 `*_delta`는 null(직전 행 없음). 원지표가 null이면 delta도 null.

**⚠ delta는 직전 '행' 기준이지 '전일' 기준이 아니다** — `lag()`는 같은 target의
captured_on 순서상 바로 앞 행과 비교한다. 수집이 하루 빠지면(장애·일시 실패) 그 다음
행의 delta는 **2일치 증감이 하나로 합쳐져** 나온다. 일 단위 정규화가 필요하면
was가 `captured_on` 간격을 같이 읽어 나눠 쓸 것.

**⚠ 두 뷰를 조인하지 말 것** — 지표 컬럼명(`likes`·`comments`·…·`content_type`)이
겹쳐서 조인하면 어느 쪽 값인지 모호해진다. 용도가 다르므로 각각 조회한다:
**overview = 최신 1일 스냅 (목록·상세 헤더)**, **timeseries = 일별 시계열 (추이 그래프)**.

### 자주 쓸 쿼리 예

```sql
-- 앱 내 알림 목록 (was 서빙 — 최신순)
SELECT id, target_id, event_type, payload, occurred_at
FROM alarm_event
WHERE user_id = :user_id
ORDER BY occurred_at DESC
LIMIT 50;

-- 캠페인 상세: 추적 게시물 추이
SELECT captured_on, likes, comments, views, saves, shares, reposts
FROM post_snapshot
WHERE short_code = (SELECT tracked_short_code FROM target WHERE id = :target_id)
ORDER BY captured_on;
```

## 4. 플로우

### 등록 (프론트 → was → monitoring)

1. 프론트 `POST /v1/monitoring/...`(was가 계약 정의) → was가 `registrationKey` 생성
2. was → monitoring `POST /api/targets` (동기, ~10s) → `targetId` + 첫 스냅샷
3. was: `app` 스키마에 `(user_id, target_id, registration_key)` 저장 → 프론트에 응답
   (첫 수집 결과 포함 — 프론트 폴링 불필요)
4. 실패 시: monitoring 에러 code를 프론트 어휘로 변환해 전달. 5xx·타임아웃이면
   같은 `registrationKey`로 재시도 가능(중복 캠페인 안 생김)

### 감지 → 자동 추적 (v2, **v2.3**: 같은 유저 이중 추적 배제 추가)

1. 02:00 monitoring 스윕: 등록 시각 이후 게시물 중 키워드 매칭 → **그 자리에서 TRACKING 전환**
   (같은 스윕에 여러 건이면 게시 시각 최신 1건. 캠페인:추적 게시물 = 1:1)
   - **(v2.3) 같은 유저 이중 추적 배제**: 매칭 후보 중 같은 `user_id`의 다른 활성 target
     (`status IN ('WATCHING','TRACKING') AND tracked_short_code IS NOT NULL`)이 이미 추적 중인
     shortcode는 후보에서 뺀다. 유저 스코프를 빼고 전역으로 하면 **다른 유저**가 먼저 추적 중인
     게시물이 조용히 빠져 버린다 — 브랜드와 대행사가 같은 인플루언서를 각자 시딩하는 건 정상
     시나리오라 이걸 막으면 안 된다. "활성"에는 **hidden**(`tracked_hidden_at` 세팅됨)·**error**
     (`fetch_failing=true`)도 포함한다 — 비공개 전환으로 hidden된 행이 재공개 시 tracking으로
     복귀하는 사이 감지가 새 행을 만들면 다시 이중 추적이 되기 때문이다. 후보가 전부 배제되면
     이번 스윕은 전환하지 않고 WATCHING을 유지 — 다음 스윕이 자연 재시도한다.
   - **알려진 한계**: was의 pending 행(등록 접수됐으나 monitoring target이 아직 없는 상태)은 이
     배제가 볼 수 없다 — monitoring은 시스템 경계상 was DB에 접근하지 않는다. 등록 접수 직후
     수 분의 창에서는 감지가 같은 게시물을 잡을 수 있다(수용된 한계).
2. `COLLECTION_STARTED` 알람 이벤트 적재(아침 레인)
3. was는 별도 명령 없이 조회 표면에서 상태 변화를 본다 — 사용자 승인 단계가 없다

### 알람 (monitoring 소유 — was 무관여)

1. monitoring이 이벤트 발생 지점 5곳에서 `alarm_event`에 적재한다
   (직접 등록·자동 전환·만료·지표 비공개·콘텐츠 접근 불가/수집 실패 — **v2.2**: 마지막 항목의
   발화 조건은 §3 `alarm_event`의 CONTENT_UNAVAILABLE 발화 시점 서술 참고)
2. monitoring 발송 크론(5분 틱)이 `dispatch_after <= now()`인 행을 유저별로 묶어 **1통**으로 보낸다.
   디바운스 10분 — 시딩 연속 등록은 잦아든 뒤 한 통으로 나간다. 단 가장 오래된 due가 30분
   (debounce-cap)을 넘기면 유입 중이어도 발송 — 시딩이 30분 넘게 이어지면 여러 통으로 나뉠 수 있다
3. 옵트아웃(`app.monitoring_email_opt_outs`)은 메일만 끈다 — 대장 행은 남는다
4. **was는 발송에 관여하지 않는다.** 앱 내 알림·히스토리 서빙만 한다(§3 `alarm_event`)

## 5. was 구현 시 주의

- **target 행은 사라지지 않는다** — 해지·만료 전부 상태 전이(**v2.2**: FAILED는 등록 시점
  전용으로 축소돼 스윕에서는 사실상 발생하지 않는다 — §3 target·status 의미 개정 참고).
  유저의 "캠페인 삭제"는 was가 자기 매핑을 지우는 것으로 완결하고, monitoring엔 DELETE(해지)만
  보낸다. 오래된 target_id 조회는 404가 아니라 종결 상태 행으로 돌아온다.
- **status·fail_reason 어휘는 monitoring이 확정** — was는 해석·분기 없이 전달
  (기존 "분류값·라벨은 생산자가 확정" 원칙).
- **읽기 전용 계정으로 쓰기 시도는 권한 오류** — 의도된 fail-closed.
- 스냅샷은 KST 기준 `captured_on` 하루 1행. 등록 직후엔 당일 1행만 있다
  (추이 그래프는 다음 날부터 의미가 생김).
- **v2 호환 주의** — 구 was의 `approve`/`reject` 호출은 **404**(경로 삭제), `userId` 없는 등록은 **400**이다.
  현재 프론트 `/v1` 미배선이라 실호출자는 없다(dev 스모크만 주의). PR②가 was 클라이언트를 정렬하고,
  monitoring의 죽은 읽기 표면(`findCandidates`·`findPendingCandidatesSince` 등 — v2에서 영구 빈 결과)도
  `detected_candidate` DROP 전에 함께 정리한다.

## 6. 알람 모듈 → app 읽기 전용 (역방향)

monitoring 알람 모듈이 analysis DB의 `app` 스키마를 **두 객체만** 읽는다. 전용 읽기 전용 롤
`alarm_reader`에 그 둘만 GRANT하고, 접속은 monitoring DB와 **별도 DataSource**다.

| 객체 | 읽는 컬럼 | 용도 |
|---|---|---|
| `app.users` | `id`, `email` | 수신자 이메일 해석 |
| `app.monitoring_email_opt_outs` | `user_id`, `event_type` | 메일 옵트아웃 필터 |

- `monitoring_email_opt_outs`는 **행 없음 = 켜짐**(기본 on). 쓰기(토글 API)는 was 소유.
- `event_type` 어휘의 정본은 monitoring(`alarm_event.event_type`과 같은 목록) — was는 그대로 저장만 한다.
- 이 둘 밖을 읽으려 하면 권한 오류로 fail-closed다(의도).

## 7. was 세션 대조 사항

### 7-1. v1.1 P2 표면 변경점 요약 (2026-07-30)

was 테스트 픽스처(`was/src/test/resources/monitoring-schema.sql`) 기준 대조:

| 표면 | 픽스처 대비 |
|---|---|
| `post_comment` | **픽스처와 동일 형태 채택** — 대조 불필요 |
| `detected_candidate.matched_keywords` | 픽스처에 없음 — **컬럼 추가 필요**(jsonb null) |
| `profile_meta` | 픽스처에 없음 — **테이블 추가 필요**(위 §3 DDL) |
| `POST /api/share/resolve` | 확장 요구 P2-8의 "등록 API 통합" 제안과 **다른 형태**(별도 전처리 API) — was 등록 플로우에서 share URL 감지 시 선호출로 배선 |

- Hiker 콜 비용(운영 참고): 스윕 계정당 3콜 + **추적 게시물당 댓글 1콜 추가**,
  share 해소는 등록 시도당 1콜. 신규 표면의 was_reader SELECT는 default privileges로
  자동 부여(V2 확립) — 별도 GRANT 불필요 확인함.
- P2 표면은 v2.0(알람 재편)과 무관하게 병렬 개발됐다 — 승인 제거·user_id·이벤트 대장은
  §2·§6의 v2.0 변경점을 참조.

### 7-2. v2.2 P1 확장 변경점 요약 (2026-07-30)

was 테스트 픽스처(`was/src/test/resources/monitoring-schema.sql`) 기준 대조:

| 표면 | 픽스처 대비 |
|---|---|
| `post_meta` / `sweep_run` / `target.tracked_hidden_at` / `target.fetch_failing` | **was 픽스처와 동일 형태 채택** — 대조 불필요 |
| `target.matched_keywords` | 픽스처에 없음 — **픽스처 갱신 필요**(jsonb null). 픽스처의 `detected_candidate.matched_keywords`는 죽은 산지(참조 금지) |

- (참고) 픽스처 target에 `user_id`·`matched_keywords`가 빠져 있음을 알림.

## 8. 브랜드 태그 모니터링 확장 — 해시태그 감지 (v2.8, 2026-08-11, 08-12 API 형태 정정·표준 REST 확장)

> ⚠️ 이 절은 §0~§7의 target/캠페인 계약과 **별도 서브시스템**을 다룬다. 브랜드 태그 모니터링
> 자체(브랜드 계정 등록·`brand_account`/`brand_tagged_post`/`brand_post_snapshot` 등 7테이블)의
> 정본은 [MON-BT 트랙](../tracks/MON-BT-브랜드-태그-모니터링.md)이고, was API는 `V1BrandAccountsController`/
> `V1BrandPostsController`(`/v1/brand-monitoring/**`)다. 해시태그 감지는 그 위에 얹는 확장이라
> 이 문서(monitoring↔was 계약)에 처음 등재한다 — 이전 브랜드 태그 모니터링 변경점은 이 문서에
> 없었다(그 자체가 갭이며, 이 절은 해시태그 확장분만 다룬다).

브랜드 계정 태그(`@브랜드핸들`)뿐 아니라 **브랜드명·계정명 및 정규화 변형 해시태그**(예:
`#브랜드명`·`#brandname`)를 매일 열거해 브랜드 관련 게시물을 자동 발견한다. 열거 결과는
monitoring이 Gemini로 브랜드 관련성을 판정(`BrandMentionJudge`, 이름 충돌 방어 — 동명이 브랜드가
아닌 다른 맥락으로 쓰인 경우 배제)한 뒤 통과분만 저장한다. monitoring 내부 신규 테이블
(`brand_hashtag`·`brand_hashtag_exclusion`·`brand_hashtag_post`)은 계약 표면이 아니다 —
was는 아래 두 표면으로만 결과를 받는다.

### 8-1. 신규 `GET /v1/brand-monitoring/accounts/{accountId}/hashtag-posts`

**08-12 정정**: 최초 설계(08-11)는 발견 게시물을 §6-1 게시물 목록에 `source: "hashtag"`로
합류시켰다. 이후 FE 결정으로 **별도 탭 전용 API로 분리**했다 — 스냅샷·댓글·팔로워 보강이 없는
별개 성격의 데이터를 tagged·direct와 같은 필터·정렬·counts 계약에 끼워 맞추면 null 필드만
늘어난다는 판단이었다. `BrandPostResponse.source`는 `"tagged"`/`"direct"` 2종으로 되돌아갔고,
§6-1 목록·`meta.counts`엔 해시태그 관련 변경이 **없다**(구현: `BrandHashtagPostAssembler`,
`V1BrandPostsController#hashtagPosts`).

소유 검증은 §6-1 목록과 같은 관용구(`requireOwnership` → 403, `findAccountOrThrow` → 404).
병합·필터·정렬·페이지네이션 없이 컷(브랜드 게시물 목록과 같은 365일 윈도우)·최신순·상한
(2000건, 폭주 방어)만 적용해 전량을 내려준다.

```json
// GET 200
{
  "data": [
    {
      "shortcode": "ABC123",
      "postUrl": "https://www.instagram.com/p/ABC123/",
      "matchedTag": "#브랜드명",
      "takenAt": "2026-08-11T14:30:00+09:00",
      "caption": "오늘 브랜드명 제품 써봤어요 ...",
      "contentType": "reels",
      "thumbnailUrl": "https://cdn.../thumb.jpg",
      "authorUsername": "some_influencer",
      "authorFullName": "인플루언서",
      "authorProfilePicUrl": "https://cdn.../author.jpg",
      "authorProfileUrl": "https://www.instagram.com/some_influencer/",
      "likes": 1200,
      "comments": 34,
      "sponsorship": "unknown",
      "firstSeenAt": "2026-08-12T03:05:00+09:00"
    }
  ]
}
```

(`meta` 키는 이 API 응답에 없다 — `ApiResponse`가 null 필드를 직렬화에서 생략한다.)

`BrandHashtagPostResponse` 필드 특성(실재하지 않는 값을 null로 채우는 대신 필드 자체를 안 낸다 —
tagged·direct 셰이프와 무관한 독립 계약):

| 필드 | 비고 |
|---|---|
| `postUrl` | 콘텐츠 타입과 무관하게 항상 `/p/{shortcode}/`(Instagram이 reels도 `/p/`를 `/reel/`로 리다이렉트) |
| `matchedTag` | 이 게시물을 찾아낸 해시태그 원문 — FE가 "#태그로 발견" 배지에 사용 |
| `likes`/`comments` | **발견 시점 관측값**(재수집 없음, 스냅샷처럼 갱신되지 않는다). null 가능 |
| `sponsorship` | **캡션 키워드만**(`BrandSponsorshipClassifier.classify(null, caption)`) — 열거 응답에 유료협찬 플래그가 실리지만 **현재 미적재**(`brand_hashtag_post`에 컬럼 없음, 후속 확장 여지). `isPaidPartnership` 필드 자체가 없다 |
| `authorFollowers`·스냅샷·댓글·`campaignIds`·`trackingStatus` 등 | **필드 자체가 없다** — 보강·병합 파이프라인 미적용(스펙 §5 보류) |
| `firstSeenAt` | 감지(브랜드 스윕 해시태그 열거) 시각 |

> (구 §8-2 `meta.counts.hashtag`는 08-12 정정으로 소거 — §8-1 전용 API로 흡수됐다. §8-3부터는
> 번호를 그대로 유지한다: 이 문서를 참조하는 다른 위치의 앵커를 깨지 않기 위해서다.)

### 8-3. `GET/PUT/POST/DELETE /v1/brand-monitoring/accounts/{accountId}/hashtag-exclusions` — 제외 문자열 관리

자사 해시태그 오탐 방지용 제외 문자열(예: 브랜드명이 흔한 일반 단어와 겹쳐 무관한 게시물을
잡을 때) 관리 API. was가 monitoring 내부 API(`/api/brands/{username}/hashtag-exclusions` 계열)를
그대로 프록시한다 — 정규화(trim·소문자·중복 제거)는 monitoring이 한다.

**08-12 유저 결정: 표준 REST 단건 조작 추가.** 기존 GET(조회)·PUT(전체 교체) 2종에 POST(단건·
다건 추가)·`DELETE {term}`(단건 삭제)·DELETE(전체 삭제) 3종을 더해 5종 표준 REST 표면이 됐다.
저장은 monitoring에서 tombstone(`deleted_at`) — 하드 삭제하면 등록 시 자동 시드(계정명 루트
기본값)가 삭제된 문자열을 되살리기 때문이다. **PUT 빈 목록 하한 가드는 폐지됐다** — 단건·전체
삭제 API가 생겨 "전부 지우기"가 더 이상 실수로만 일어나는 상태가 아니다(구 v2.8 422 가드 제거).

**⚠️ 판정 자체는 여전히 비소급** — 이미 저장된 발견 게시물의 verdict는 불변이라, term을
추가해도 이미 노출 중인 게시물이 사라지지 않는다(SELF로 새로 걸러지는 건 다음 스윕의 신규
발견분부터). **다만 조회 시점 필터는 즉시 반영된다(08-12 신규)** — was가 `/hashtag-posts`
조회 때마다 활성 제외 문자열을 다시 읽어 게시자 username에 포함되면 그 자리에서 걸러낸다
(§8-1 참조). 그래서 term을 지우면 저장된 verdict와 무관하게 해당 게시물이 다시 뜨고, term을
다시 추가하면 즉시 다시 숨는다 — "전체 삭제"로 자사 게시물이 쏟아지는 비가역 오염을 조회 필터가
막아 주므로, 하한 가드 폐지가 안전하다.

```json
// GET 200
{ "terms": ["일반단어1", "일반단어2"] }

// PUT 요청 (전체 교체, 빈 배열도 허용 — 전체 비활성화와 같다)
{ "terms": ["일반단어1"] }
// PUT 204

// POST 요청 (단건·다건 추가 — tombstone 재활성)
{ "terms": ["새단어"] }
// POST 204

// DELETE /hashtag-exclusions/{term} — 단건 삭제(tombstone), 없어도 204(멱등)
// DELETE /hashtag-exclusions — 전체 삭제(tombstone)
```

| 상황 | HTTP | 비고 |
|---|---|---|
| 정상 | GET 200 / PUT·POST·DELETE 204 | PUT 빈 목록도 204(2026-08-12부터 허용) |
| 소유하지 않은 `accountId` | 403 | was 측 소유권 검증(`requireOwnership` — 유저의 활성 브랜드 연결에 없으면) |
| `accountId`가 유효한 브랜드가 아님(브랜드 비정합) | 404 | was 측 `findAccountOrThrow` 또는 monitoring의 `BRAND_NOT_FOUND`(브랜드 미등록·비ACTIVE) 둘 다 404로 수렴 |
| monitoring 접속 불능 | 503 | `Retry-After: 5` 동반(다른 monitoring 연동 엔드포인트와 공통 매핑, `V1ExceptionAdvice`) |

### 8-3-1. `GET/PUT/POST/DELETE /v1/brand-monitoring/accounts/{accountId}/hashtag-tags` — 태그 셋 관리 (v2.9, 2026-08-12, 08-12 표준 REST 확장)

**유저 결정: 자동 유도만 → 유저 입력 허용으로 전환.** 등록 시 자동 유도되는 태그 3종(#브랜드명·
#계정명 루트·#전체계정명, §2)은 여전히 시드되지만, 이제 브랜드 소유자가 감지 대상 해시태그
전체를 직접 추가·삭제할 수 있다. was가 monitoring 내부 API(`/api/brands/{username}/hashtag-tags`
계열)를 그대로 프록시한다 — 정규화(trim·선행 `#` 제거·소문자·중복 제거)와 유효 문자 검증은
monitoring이 한다.

**08-12 유저 결정: 표준 REST 단건 조작 추가.** 기존 GET·PUT 2종에 POST(단건·다건 추가)·
`DELETE {tag}`(단건 삭제)·DELETE(전체 삭제) 3종을 더해 5종 표준 REST 표면이 됐다.
**PUT 빈 목록 하한 가드는 폐지됐다**(전체 삭제 API가 정식 경로가 됐으므로) — 빈 목록 PUT은
브랜드 태그 감지를 전부 끄는 것과 같다. **POST는 PUT과 다르게 빈 입력을 여전히 422로 거부한다**
— "추가할 태그가 없다"는 요청 자체가 실수일 확률이 높고, 전체를 비우는 명시적 의도는 DELETE
전체가 담당하기 때문이다.

**⚠️ 비소급** — 태그 추가는 다음 새벽 스윕부터 감지가 시작된다(소급 백필은 최대 4페이지, 스펙
§1 등록 백필과 같은 한도 — `monitoring.brand.hashtag.max-pages` 기본값). 태그 삭제는 이후
발견분만 중단되고, 이미 저장된 발견 게시물은 그대로 유지된다(verdict 불변).

**⚠️ 삭제는 tombstone** — monitoring `brand_hashtag`에 `deleted_at`이 채워진 채 행이 남는다.
등록 replay가 부르는 자동 시드(`insertTags`, `ON CONFLICT DO NOTHING`)는 이 tombstone 행에
막혀 유저가 지운 태그를 되살리지 못한다. 지운 태그를 다시 쓰려면 PUT(전체 교체)이나 POST(추가)로
재추가해야 한다(둘 다 tombstone 해제 UPSERT라 정상 동작).

```json
// GET 200
{ "tags": ["cclime", "끌리메", "cclime_official"] }

// PUT 요청 (전체 교체, 빈 배열도 허용 — 태그 감지 전체 중지와 같다)
{ "tags": ["cclime", "새태그"] }
// PUT 204

// POST 요청 (단건·다건 추가 — tombstone 재활성)
{ "tags": ["새태그"] }
// POST 204

// POST 400 — 정규화 결과가 빈 입력(PUT은 허용하지만 POST는 거부)
{ "code": "VALIDATION_FAILED", ... }

// PUT·POST 400 — 무효 문자(IG 해시태그 불가 문자) 포함 태그
{ "code": "VALIDATION_FAILED", ... }

// DELETE /hashtag-tags/{tag} — 단건 삭제(tombstone), 없어도 204(멱등)
// DELETE /hashtag-tags — 전체 삭제(tombstone, 브랜드 태그 감지 완전 중지)
```

| 상황 | HTTP | 비고 |
|---|---|---|
| 정상 | GET 200 / PUT·POST·DELETE 204 | PUT 빈 목록도 204(2026-08-12부터 허용) |
| POST 정규화 결과 빈 입력 | 400 `VALIDATION_FAILED` | "추가할 게 없다"는 요청 자체가 실수 — PUT과 다른 규칙. monitoring 내부는 422(`InvalidHashtagException`)지만 was 공용 매핑(`V1ExceptionAdvice` — 404·5xx 외 4xx는 400 수렴)이 400으로 내린다 |
| PUT·POST 무효 문자 포함 태그 | 400 `VALIDATION_FAILED` | 유저 입력이라 절삭하지 않고 통째로 거부(자동 유도와 다른 규칙) — 공백·`.` 등 IG 해시태그 불가 문자(`[\p{L}\p{N}_]+` 전체 일치 아니면 거부) |
| 소유하지 않은 `accountId` | 403 | was 측 소유권 검증(`requireOwnership`) |
| `accountId`가 유효한 브랜드가 아님 | 404 | was 측 `findAccountOrThrow` 또는 monitoring의 `BRAND_NOT_FOUND` 둘 다 404로 수렴 |
| monitoring 접속 불능 | 503 | `Retry-After: 5` 동반 |

### 8-4. FE 공유 필요

프론트 공유가 아직 안 된 신규 UI 표면 3가지:

- **해시태그 발견 게시물 "별도 탭"** — §8-1 전용 API(`GET .../hashtag-posts`)를 §6-1 게시물
  목록과 나란한 새 탭으로 노출. 스냅샷·댓글·팔로워가 없는 데이터라 tagged·direct 카드와 다른
  레이아웃이 필요하고(성과 지표 없음, `likes`/`comments`는 발견 시점 스냅 값), `matchedTag`로
  "#태그로 발견" 배지를 그릴 수 있다.
- **제외 문자열 관리 UI** — §8-3 API로 자사 태그 오탐 문자열을 브랜드 소유자가 직접 추가·삭제
  (조회·전체 교체·단건 추가·단건 삭제·전체 삭제 5종). 편집이 조회 필터에는 즉시 반영된다는 점을
  강조할 것(저장된 verdict는 비소급이지만 조회는 즉시).
- **태그 셋 관리 UI** — §8-3-1 API로 감지 대상 해시태그를 브랜드 소유자가 직접 추가·삭제
  (조회·전체 교체·단건 추가·단건 삭제·전체 삭제 5종). 추가는 다음 새벽부터 감지 시작(비소급),
  삭제는 tombstone(재추가하면 복구되지만 그 전까지 발견 중단)이라는 두 비소급 규칙을 FE 문구에
  반드시 반영할 것. 전체 삭제 = 브랜드 해시태그 감지 전체 일시 중지.
