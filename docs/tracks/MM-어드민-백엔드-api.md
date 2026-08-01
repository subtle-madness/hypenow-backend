# MM — 어드민 백엔드 API

- **소속 트랙군**: 단독 (어드민 프론트 `apps/admin` 계약 — 2026-07-31 변경요청서)
- **의존**: —
- **상태**: 🔨 (P0+P1+P2 구현 완료, PR 대기)

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
