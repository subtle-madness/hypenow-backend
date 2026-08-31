# MM — 어드민 백엔드 API

- **소속 트랙군**: 단독 (어드민 프론트 `apps/admin` 계약 — 2026-07-31 변경요청서)
- **의존**: —
- **상태**: ✅ 완결 (본편 08-01: #290→#292→#295 운영. 후속 08-02: #298 머지 → #302 test 배포·검증 → #306 운영 승격, 운영 CD success — Flyway V20260802074604 적용·campaigns 401 게이트·health 확인. 승격 중 발견한 #287 마이그레이션 out-of-order는 #300 재채번으로 해소. 잔여는 프론트 B1~B3 회신 반영뿐)

## 내용

어드민(admin.hypenow.io) 실모드 개통을 위한 was 백엔드 — 설계: [specs/2026-08-01-admin-backend-api-design.md](../superpowers/specs/2026-08-01-admin-backend-api-design.md)

- **P0**: `GET /v1/me`에 `role`("admin"|"user") + `X-Act-As-User` impersonation(어드민 세션 한정,
  GET/HEAD 전용 405 가드, 대상 부재 404, SecurityContext 스왑, `app.admin_audit_logs` 감사 기록,
  비어드민 시도 WARN). `app.users.last_active_at` 신설(5분 스로틀 갱신).
- **P1**: `GET /v1/admin/users`(목록+카운트+health) · `/v1/admin/users/{id}`(상세+events 100건) ·
  `/v1/admin/monitoring/registrations`(활성 행 상태 5종 + counts). health와 rows는
  `AdminMonitoringHealthService` 단일 모집단(계약 불변식).
- **P2**: `GET /v1/admin/audit-logs` 조회. 8절 통합 검색은 **미착수**(요청서 스스로 보류).
- 프론트 회신: A1 host-only 쿠키 / A2 숫자 id / A3 last_active_at 신설 / A4~A9 및 잠정값
  B1(감지 전 contentType="reels")·B2(url pending 핸들 "")·B3(혼합 등록 "외 N건") — 설계 §0.

## 08-02 후속 (프론트 변경요청서 4-2-6절 외)

- **`GET /v1/admin/campaigns` 신설** — 파라미터 없이 전체 캠페인 createdAt 내림차순.
  행 필드 `id, name, userId, userName, createdAt, status, registrationCount, seedingTarget` + meta.total.
  - `status`(pending|active|ended|no_date)는 서버 판정(`AdminCampaignStatus` 순수 함수, KST 오늘 기준,
    경계일=active) — 유저 표면 계약(6.25)의 "상태는 클라 파생"과 별개로 어드민 표면만 서버가 유도.
  - `seedingTarget` ← `monitoring_campaigns.seeding_count`(프론트 어휘가 target일 뿐 같은 값, nullable).
  - `registrationCount`는 취소 포함 전량(`countItems`·users 목록 `monitoringCount`와 동일 계약),
    IN절 배치 집계로 N+1 없음. userName은 registrations와 동형의 `findByIds` Java 결합.
- **`GET /v1/admin/users` 목록에 `companyName` 추가** — 상세엔 이미 있던 필드의 목록 노출(N+1 방지
  요청). `AdminUserSummary`가 직접 들고 상세(`AdminUserDetail`)는 Summary에서 물려받도록 바꿔
  두 표면이 구조적으로 어긋날 수 없게 함. 미설정은 상세와 동일하게 ''(NOT NULL DEFAULT '').

## 08-31 후속 (유저별 기능 플래그)

프론트 요청 5건 — 저장·조회뿐이라 기존 엔드포인트 동작 변경 없음, 프론트가 읽기 전까지 무영향.

- **`app.users.feature_overrides jsonb NOT NULL DEFAULT '{}'` 신설**(V20260831032920, expand-only).
  "미설정"을 null과 `{}` 두 값으로 쪼개지 않기 위해 NOT NULL — 응답 계약(항상 객체·null 금지)이
  저장 계층에서부터 성립한다.
- **`GET /v1/me`·`GET /v1/admin/users`(목록)·`GET /v1/admin/users/{id}`(상세)에 `featureOverrides` 추가**
  — 컬럼 값 그대로, 비면 `{}`. `/v1/me`는 role과 같이 매 요청 DB를 읽으므로(`UserProfile`)
  **어드민이 바꾼 값이 세션 갱신 없이 즉시 반영**된다(재로그인·세션 재발급 불필요).
  목록 노출은 어드민 "유저 기능" 매트릭스 화면 요청(08-31) — 상세에만 있으면 화면 진입마다 유저
  수만큼 상세 요청이 나간다. companyName(08-02)과 동일하게 `AdminUserSummary`가 직접 들고
  상세는 물려받아 두 표면이 구조적으로 어긋날 수 없게 했다. 목록 쿼리는 이미 같은 컬럼 집합을
  읽고 있어(`AdminUserRepository.COLUMNS` 공유) DB 왕복·조회 비용은 늘지 않는다.
- **`PUT /v1/admin/users/{id}/features` 신설** — `{"overrides": ...}` **전체 교체**(PATCH 병합 아님).
  값 타입은 `boolean | string[]`만, 그 외 400 `VALIDATION_FAILED`. **키 문자열은 검증하지 않는다**
  (기능 목록·기본값의 정본은 프론트 — DB가 어휘를 고정하면 기능이 늘 때마다 마이그레이션이 따라붙는다).
  본문 상한 8KB. 인가는 기존 `/v1/admin/**`과 동일(SecurityConfig `hasRole(ADMIN)` +
  `AdminRoleFreshnessFilter` DB role 재확인) — 별도 가드 없음. 쓰기라 CSRF 토큰(`X-XSRF-TOKEN`)이 필요하다.
  응답은 요청 원문이 아니라 **DB에 저장된 값**을 되돌려준다(jsonb 키 순서 정규화·중복 키 제거).
- **감사 기록은 기존 `app.admin_audit_logs` 재사용** — 컬럼 추가 없이 `path`로 구분된다.
  `ActAsUserFilter`는 `/v1/admin/*` 경로를 애초에 기록하지 않으므로(어드민 표면은 사칭 의미가 없다),
  `path`가 `/v1/admin/users/{id}/features`인 행은 기능 플래그 변경뿐이다. 변경과 기록은 한 트랜잭션 —
  act-as 기록의 best-effort와 달리 여기는 상태를 바꾸는 요청이라 "기록 없는 변경"을 허용하지 않는다.
