# monitoring ↔ was 계약 — was 개발자용

> **living 문서** — monitoring 모듈이 was에 제공하는 계약의 정본. 구현과 함께 갱신한다.
> 배경·설계 근거는 [specs/2026-07-28-monitoring-module-design.md](../superpowers/specs/2026-07-28-monitoring-module-design.md) 참조.
> 상태: v0.1 초안 (구현 미착수 — 필드·에러 어휘는 구현 중 조정될 수 있음, 변경 시 이 문서가 먼저 갱신됨)

## 0. 한 장 요약

- **쓰기(명령)는 전부 monitoring 내부 API** — 등록·승인·거절·연장·해지 5개.
  was는 monitoring DB에 어떤 쓰기도 할 수 없다(읽기 전용 계정).
- **읽기(조회)는 전부 monitoring DB `public` 스키마 SELECT** — 조회용 API는 없다.
  목록·상태·후보·추이·이메일 알람용 신규 감지분 조회 모두 SELECT.
- **target = 캠페인(등록) 1건** — 같은 인플루언서를 여러 캠페인이 각자 키워드로 등록
  가능. 스냅샷은 계정·게시물 단위라 캠페인 사이에 공유된다(target → username /
  tracked_short_code로 조인해서 본다).
- was가 자기 `app` 스키마에 보관할 것: `(user_id, target_id, registration_key)` 매핑
  (+ 알람 발송 워터마크). target_id는 **논리 참조**(FK·크로스 DB 조인 금지 —
  `saved_influencers.handle` 관용구와 동일).

## 1. 접속 정보

| 항목 | 값 |
|---|---|
| 명령 API | `http://monitoring:8083` — **전용 도커 네트워크 `monitoring-net`** 경유(was 컨테이너가 이 네트워크에 소속돼야 이름이 해석됨). 호스트 포트·Caddy 미노출 |
| 인증 | **없음 — 네트워크 소속이 곧 인증.** `monitoring-net`에는 was와 monitoring만 소속. 헤더·토큰 불필요 |
| dev 환경 | `http://dev-monitoring:8083` — `dev-monitoring-net`(dev-was와 둘만 소속). 운영 monitoring은 dev에서 DNS 해석 자체가 안 됨(오배선 fail-closed) |
| 조회 DB | `postgres` 인스턴스의 `monitoring` DB, 읽기 전용 계정(`public` 스키마만 GRANT). dev는 dev-postgres의 monitoring DB |
| 타임아웃 권고 | 등록 POST 10s (동기 Hiker 수집 포함) / 나머지 명령 5s (승인도 즉시 수집 포함 시 10s) |

## 2. 명령 API

공통 에러 응답:

```json
{ "code": "SUBJECT_NOT_FOUND", "message": "계정을 찾을 수 없음: @foo" }
```

| code | HTTP | 의미 |
|---|---|---|
| `VALIDATION` | 400 | 요청 형식·필수 필드 위반 |
| `TARGET_NOT_FOUND` / `CANDIDATE_NOT_FOUND` | 404 | 해당 id 없음 |
| `SUBJECT_NOT_FOUND` | 404 | 인스타에 계정/게시물이 없음 (등록 시) |
| `PRIVATE_ACCOUNT` | 422 | 비공개 계정이라 수집 불가 |
| `INVALID_STATE` | 409 | 상태상 불가한 명령 (예: 종결된 target 승인) |
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
  "type": "POST",
  "shortCode": "DAbCdEfGhIj",
  "expiresAt": "2026-08-28T23:59:59+09:00"
}
```

동작: 검증 → **동기로 첫 Hiker 수집**(계정 존재 확인 + 첫 스냅샷 적재) → 응답.
같은 `registrationKey` 재호출은 새로 만들지 않고 기존 target을 200으로 반환(크래시
복구용). 키가 다르면 같은 계정·키워드라도 별도 캠페인이 생긴다.

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

### 2-2. 후보 승인 — `POST /api/targets/{id}/candidates/{candidateId}/approve`

WATCHING 캠페인의 PENDING 후보를 승인 → 그 게시물로 TRACKING 전환(즉시 1회 수집).

```json
// 200
{ "targetId": 17, "status": "TRACKING", "trackedShortCode": "DAbCdEfGhIj" }
```

### 2-3. 후보 기각 — `POST /api/targets/{id}/candidates/{candidateId}/reject`

후보만 REJECTED로 닫고 WATCHING 지속. `// 200 { "candidateId": 3, "status": "REJECTED" }`

target이 활성(WATCHING/TRACKING)이 아니면 409 `INVALID_STATE` — 종결된 캠페인의 잔여 후보는
거절 불가(이미 감시가 끝났으므로).

### 2-4. 기간 연장 — `PATCH /api/targets/{id}`

```json
{ "expiresAt": "2026-09-30T23:59:59+09:00" }   // 200 { "targetId": 17, "expiresAt": "…" }
```

### 2-5. 해지 — `DELETE /api/targets/{id}`

상태를 CANCELED로 전이(행·스냅샷 보존 — 물리 삭제 아님). 멱등: 이미 종결이면 현재
상태 그대로 200. `// 200 { "targetId": 17, "status": "CANCELED" }`

## 3. 조회 표면 (`public` 스키마 — 읽기 전용 SELECT)

아래 테이블·뷰가 계약이다. 여기 없는 객체는 내부 구현이므로 조회하지 말 것
(monitoring DB 안에서의 조인은 자유. 단 app 스키마·분석 결과와의 크로스 DB 조인은
기존 규칙대로 금지 — 조합은 was 코드에서).

### target — 캠페인 (등록당 1행)

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `id` | bigint PK | target_id — was가 매핑에 보관하는 그 값 |
| `type` | text | `ACCOUNT` / `POST` |
| `username` | text | 계정 핸들 (POST 등록도 소유 계정 기록) |
| `short_code` | text null | POST 등록 시의 게시물 |
| `keyword_rule` | jsonb | 키워드 규칙 `{"and":[…],"any":[…],"exclude":[…]}` (ACCOUNT 전용). 매칭 = and 전부 ∧ (any 비었거나 하나 이상) ∧ exclude 전무 — 부분 문자열·대소문자 무시·캡션 전문 |
| `status` | text | `WATCHING` / `TRACKING` / `EXPIRED` / `CANCELED` / `FAILED` |
| `tracked_short_code` | text null | 승인(또는 직접 등록)된 추적 게시물 |
| `tracked_since` | timestamptz null | TRACKING 전환 시각 |
| `registration_key` | text unique | was가 넘긴 멱등 키 |
| `expires_at` | timestamptz | 만료 시각 (PATCH로 연장 가능) |
| `registered_at` / `closed_at` | timestamptz | 등록 / 종결(EXPIRED·CANCELED·FAILED) 시각 |
| `last_fetched_at` | timestamptz null | 마지막 수집 시각 |
| `fail_reason` | text null | FAILED 사유 (`SUBJECT_NOT_FOUND` 등 §2 어휘) |

### detected_candidate — 감지 후보 (캠페인 소속)

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `id` | bigint PK | candidate_id (승인/거절 명령에 사용) |
| `target_id` | bigint | 소속 캠페인 |
| `short_code` | text | 감지된 게시물 |
| `detected_at` | timestamptz | 감지 시각 (02:00 배치) |
| `caption_excerpt` | text | 키워드 주변 캡션 발췌 (FE 노출용) |
| `status` | text | `PENDING` / `APPROVED` / `REJECTED` |

같은 (target_id, short_code)는 한 번만 생성 — 거절해도 재감지로 되살아나지 않는다.

### profile_snapshot / post_snapshot — 관측치 (계정·게시물 단위, 캠페인 간 공유)

```
profile_snapshot(username, captured_on date, followers, following, media_count, …)
                 PK (username, captured_on) — 일 1회 upsert

post_snapshot(username, short_code, captured_on date, content_type REELS|FEED,
              likes, comments, views, saves, shares, reposts)
              PK (short_code, captured_on) — 일 1회 upsert
```

- 지표 6종: 좋아요·댓글·조회·저장·공유·리포스트. **취득 불가 지표는 null**
  (예: 피드 조회수 — Hiker 필드 매핑은 구현 시 확정, null 규칙 준수 필수).
- 캠페인 추이는 target을 조인해 본다: `target.username` → profile_snapshot,
  `target.tracked_short_code` → post_snapshot.

### 조회 뷰 (구현 시 확정 — 이름·컬럼은 초안)

- `v_target_overview` — target + 최신 프로필/게시물 스냅샷 + PENDING 후보 수.
  캠페인 목록 화면은 이것 하나로 서빙 가능하게 유지한다.
- `v_target_timeseries` — target_id × captured_on 일별 지표 + 전일 대비 증감.
  파생 집계(증감률·이동평균)는 뷰 안에서 계산돼 나온다.

### 자주 쓸 쿼리 예

```sql
-- 이메일 알람 크론 (09:00): 워터마크 이후 신규 감지 후보
SELECT c.id, c.target_id, c.short_code, c.caption_excerpt, c.detected_at, t.username
FROM detected_candidate c JOIN target t ON t.id = c.target_id
WHERE c.status = 'PENDING' AND c.detected_at > :last_notified_at
ORDER BY c.detected_at;

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

### 감지 → 승인

1. 02:00 monitoring 배치: 키워드 매칭 → `detected_candidate(PENDING)` 적재
2. FE 목록에 "감지됨 — 승인 대기" 노출 (was가 SELECT로 서빙)
3. 사용자 승인 → was → `POST .../approve` → TRACKING 전환
   (거절 → `.../reject` → WATCHING 지속)

### 이메일 알람 (was 소속 09:00 크론)

1. 위 §3 쿼리로 워터마크 이후 신규 PENDING 조회
2. `app` 매핑으로 수신 유저 확인 → 알람 on 유저만 발송
3. 발송 워터마크를 `app` 스키마에 갱신 (중복 발송 방지는 전적으로 was 책임)

## 5. was 구현 시 주의

- **target 행은 사라지지 않는다** — 해지·만료·실패 전부 상태 전이. 유저의 "캠페인
  삭제"는 was가 자기 매핑을 지우는 것으로 완결하고, monitoring엔 DELETE(해지)만
  보낸다. 오래된 target_id 조회는 404가 아니라 종결 상태 행으로 돌아온다.
- **status·fail_reason 어휘는 monitoring이 확정** — was는 해석·분기 없이 전달
  (기존 "분류값·라벨은 생산자가 확정" 원칙).
- **읽기 전용 계정으로 쓰기 시도는 권한 오류** — 의도된 fail-closed.
- 스냅샷은 KST 기준 `captured_on` 하루 1행. 등록 직후엔 당일 1행만 있다
  (추이 그래프는 다음 날부터 의미가 생김).
