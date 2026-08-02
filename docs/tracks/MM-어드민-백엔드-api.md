# MM — 어드민 백엔드 API

- **소속 트랙군**: 단독 (어드민 프론트 `apps/admin` 계약 — 2026-07-31 변경요청서)
- **의존**: —
- **상태**: 🟢 후속 진행 (본편은 08-01 완결: #290 머지 → #292 test 배포·dev 검증 → #294 백머지 → #295 운영 승격, 운영 CD success. 운영 role 부여는 불필요였음 — 팀 계정 2개가 기존 ADMIN. 08-02 프론트 후속 요청 2건 구현 — PR 대기. 잔여: 후속 PR 머지·배포 + 프론트 B1~B3 회신 반영)

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
