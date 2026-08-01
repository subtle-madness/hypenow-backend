# 어드민 백엔드 API 설계 (2026-08-01)

> 상태: 🟢 활성

프론트 변경요청서(2026-07-31, `apps/admin` 계약)에 대한 백엔드 설계. 요청서의 P0(role·act-as)
+ P1(admin 조회 3종) + P2 중 audit-logs 조회까지 구현한다. P2 통합 검색(8절)은 요청서 스스로
"스키마 확정 아님, 착수 전 재협의"라 명시해 **이번 범위에서 제외**한다.

## 0. 확인 요청(요청서 9절) 회신

| # | 회신 |
|---|---|
| A1 | 세션 쿠키는 **host-only**다 — `application.yml`의 `server.servlet.session.cookie`에 `domain` 미설정. 어드민 로그인이 운영 세션을 덮지 않는다. 그대로 유지한다 |
| A2 | `userId`는 `bigserial` 숫자(`app.users.id`) — 문자열화하면 `^[0-9]+$`로 프록티 패턴 안에 있다. 프론트 패턴 변경 불필요 |
| A3 | 현재 미기록. **`app.users.last_active_at` 신설** + 세션 인증된 요청마다 갱신(스팸 방지 5분 스로틀, 요청당 UPDATE 아님). 배포 이전 활동은 소급 불가 — 초기엔 전원 `null`에서 시작 |
| A4 | 제안 수용: `인플루언서 저장 (@핸들)` |
| A5 | 상한 **최근 100건** (at 내림차순 100건에서 절단) |
| A6 | 제안 수용: N=**7일**, M=**48시간**(일일 스윕 기준 2회 연속 미갱신) |
| A7 | 현재 활성 추적 행은 수십 건 규모(클로즈베타) — 페이지네이션 불요. 수천 단위가 보이면 그때 추가 협의 |
| A8 | 보존 **90일**(일일 삭제 배치), 묶음 기록 없이 **전량 기록**(조회 화면에서 묶는 게 낫다고 판단되면 프론트 표시 단계에서) |
| A9 | 문서대로 **`FORBIDDEN`** 유지. (백엔드엔 `METHOD_NOT_ALLOWED` 코드가 이미 있지만 프론트 유니언 밖이라 쓰지 않는다) |

### 프론트에 되물을 것 (구현은 아래 잠정값으로 진행)

- **B1**: 계정 모드 등록 행은 게시물 감지 전(`detecting`/`detect_stalled`) `contentType`을 특정할 수
  없다(등록 입력에 릴스/피드 구분이 없음). 잠정: 감지 전 행은 `"reels"`로 내린다(감지 후엔
  post_meta 실제 타입). 프론트가 `contentType` union을 넓히거나 nullable로 바꿔주면 정정한다.
- **B2**: url 모드 등록 직후(target 미확정 `collecting`) 행은 핸들을 아직 모른다 — 유저 표면과 동일하게
  빈 문자열 `""`로 내린다.
- **B3**: `monitoring_registered` 이벤트에서 한 요청에 여러 건이 섞이면
  `모니터링 등록 (@첫핸들 외 N건)` 형식을 쓴다(모두 같은 핸들이면 요청서 형식 그대로).

## 1. 인증·인가

- `app.users.role`(V11, `USER`/`ADMIN`)이 이미 있다 — 신규 컬럼 없음. `/v1/me` 응답에
  `role: "admin" | "user"` 필드 추가(DB 값 소문자 매핑).
- `SecurityConfig` @Order(2) 세션 체인에 `.requestMatchers("/v1/admin/**").hasRole("ADMIN")` 추가
  (anyRequest 앞). 미인증 401은 기존 진입점, **인가 실패 403은 신규 AccessDeniedHandler**로
  envelope(`FORBIDDEN`) JSON을 쓴다(현재는 403 빈 본문).
- 기존 `/admin/**` Basic 체인·codes 토큰 체인은 무관하게 유지(경로가 `/v1/admin/**`과 겹치지 않음).

## 2. X-Act-As-User (impersonation)

`OncePerRequestFilter`를 @Order(2) 체인의 AuthorizationFilter **뒤에** 등록.

- 헤더 없음 → 통과.
- 세션 principal이 ADMIN이 아님 → **무시 + WARN 로그**(session id, 대상 id, 경로) — 운영 도메인
  프록시 우회 공격 신호.
- ADMIN + 헤더 있음:
  - 경로가 `/v1/admin/**` → 무시(어드민 표면은 사칭 의미 없음. 인가는 이미 어드민으로 판정됨).
  - 메서드가 GET/HEAD 아님 → **405** + `Allow: GET, HEAD` + envelope `FORBIDDEN`
    ("유저 뷰는 조회 전용이에요.").
  - 대상 id 숫자 아님/유저 부재 → **404** envelope `NOT_FOUND`.
  - 정상 → 요청 스코프 SecurityContext의 principal을 **대상 유저의 AppUserDetails로 교체**
    (권한은 대상 유저의 실제 role). 이후 모든 컨트롤러(`/v1/me` 포함)는 무수정으로 대상 유저
    기준 응답 — 요청서의 "모든 GET 적용, /me 예외 없음" 계약이 자동 충족된다.
  - **감사 기록**: `app.admin_audit_logs`에 (admin_id, target_user_id, path, at) INSERT.
    쿼리스트링은 기록하지 않는다(개인정보 유입 차단, 요청서가 백엔드 판단 위임).
- CSRF는 GET/HEAD라 비관여. 세션 인가 판정(hasRole)은 필터보다 앞서 이미 어드민 본인으로
  끝났으므로 principal 교체가 어드민 인증을 잠그지 않는다(요청서 3절의 기대와 일치).

## 3. 신규 스키마 (app, expand-only)

`V<UTC타임스탬프>__admin_last_active_and_audit.sql`:

```sql
ALTER TABLE app.users ADD COLUMN IF NOT EXISTS last_active_at timestamptz;
CREATE TABLE IF NOT EXISTS app.admin_audit_logs (
  id bigserial PRIMARY KEY,
  admin_id bigint NOT NULL,
  target_user_id bigint NOT NULL,
  path text NOT NULL,
  at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ... (target_user_id, at DESC), (admin_id, at DESC), (at)  -- 보존 삭제용
```

- `last_active_at` 갱신: 인증된 요청에서 5분 스로틀로 UPDATE(필터, 실패는 무시 — 관측 부가 기능이
  본 요청을 죽이면 안 됨).
- 감사 로그 보존: 90일 경과분 일일 삭제 스케줄러(was 내, `@Scheduled`).

## 4. 어드민 조회 API (`com.celfit.was.v1.admin`)

응답 시각은 전부 `KstTimestamps`(+09:00), envelope는 기존 `ApiResponse`.

### 공유 컴포넌트: AdminMonitoringHealthService

4절 `health`와 6절 `rows`가 **같은 모집단**을 쓰도록 단일 서비스로 구현한다.

- **활성 행** = `app.monitoring_items` 중 `canceled_at IS NULL` AND
  (target 미확정이면 기간 미만료 pending, target 있으면 `status IN ('WATCHING','TRACKING')`).
- 행 상태(우선순위 순):
  1. `error` — TRACKING + `fetch_failing`
  2. `hidden_or_deleted` — TRACKING + `tracked_hidden_at IS NOT NULL` *(주: 유저 표면 ItemStatus는
     hidden을 error보다 우선하지만, 어드민 계약은 error 우선 — 요청서 표 순서를 따른다)*
  3. `detect_stalled` — WATCHING(또는 account pending)이고 등록 후 7일 경과, 감지 없음
  4. `collect_stalled` — TRACKING이고 최신 `post_snapshot.captured_on`이 48시간 이전
  5. `healthy`
- `lastCollectedAt` = 해당 게시물의 최신 `post_snapshot.captured_on`(KST 자정 시각으로 표기,
  스냅샷이 일 단위라 시각 해상도는 날짜). 스냅샷 없으면 `null`.
- monitoring 미기동(dev, `monitoring.enabled=false`) → 활성 행 0건, 전원 `healthy`/`ok`.
- 크로스 DB 조인 금지 준수: app 행과 monitoring target/snapshot을 각각 조회해 Java에서 결합
  (기존 TrackingItemAssembler 관용구).

### GET /v1/admin/users

- `query`(이메일·이름 부분일치, ILIKE), `page`/`limit`(≤100), `sort`는 미지원 값 무시(항상
  `created_at DESC`). `meta.total/limit/offset`.
- 카운트는 페이지 내 유저 id IN절 집계: `campaignCount`(monitoring_campaigns),
  `monitoringCount`(monitoring_items 전량 — 취소 포함 누적), `savedCount`(saved_contents +
  saved_influencers). `health`는 위 서비스.

### GET /v1/admin/users/{id}

- Summary 전체 + `companyName`, `jobTitle`(null이면 `""`), `signupCode`(signup_codes.used_by 역참조,
  없으면 null), `events`(최근 100건, at 내림차순).
- events 소스: `signup`(users.created_at + signup_codes), `campaign_created`(monitoring_campaigns —
  삭제된 캠페인은 이력에서 소실, 한계로 문서화), `monitoring_registered`(monitoring_registrations +
  entries), `content_saved`(saved_contents + 분석 결과 contents로 핸들·유형 보강 — 조인 금지,
  Java 결합), `influencer_saved`(saved_influencers).

### GET /v1/admin/monitoring/registrations

- `status`(5종 외 400 `VALIDATION_FAILED`), `userId` AND 결합.
- `rows` + `counts`(5키 전부, status 필터 미반영·userId 필터만 반영).
- 정렬: 심각도(error→hidden→detect_stalled→collect_stalled→healthy) → `lastCollectedAt` 오래된 순
  (null 최우선 = 가장 오래됨 취급).
- `campaignName` 미지정은 `"(캠페인 미지정)"`, `userName`은 users 조인.

### GET /v1/admin/audit-logs

- `adminId`/`targetUserId` 필터 + 페이지네이션, `at` 내림차순.

## 5. 테스트

- ItemStatus 방식의 순수 함수 단위 테스트: 어드민 상태 유도(경계: 7일/48시간, 우선순위).
- 통합(Testcontainers): 어드민 403/401 게이트, act-as(비어드민 무시+본인 응답, 쓰기 405,
  부재 404, `/v1/me` 스왑, 감사 기록), users 목록/상세, registrations 필터·counts 불변,
  audit-logs 조회.

## 6. 비범위

- 8절 통합 검색(요청서 스스로 보류).
- 운영 프론트 프록시 수정(별도 저장소), 어드민 role 부여(운영 DB 수동 UPDATE — 배포 후 운영 작업).
