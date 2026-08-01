-- 어드민 백엔드 API P0(설계 2026-08-01 §3) — 유저 최종 활동 시각 + 어드민 act-as 감사 로그.
-- expand-only: 기존 컬럼·테이블 변경 없음. 배포 이전 활동은 소급 불가(A3) — 신규 컬럼은 전원 null에서 시작.
ALTER TABLE app.users ADD COLUMN IF NOT EXISTS last_active_at timestamptz;

-- act-as(X-Act-As-User) 사칭 기록 — 보존 90일(A8, 일일 삭제 배치는 was 스케줄러). 쿼리스트링은 기록하지 않는다
-- (개인정보 유입 차단 — path만).
CREATE TABLE IF NOT EXISTS app.admin_audit_logs (
    id              bigserial PRIMARY KEY,
    admin_id        bigint NOT NULL,
    target_user_id  bigint NOT NULL,
    path            text   NOT NULL,
    at              timestamptz NOT NULL DEFAULT now()
);
-- 유저 상세(§4)의 events 조회, 어드민별 조회(§4 audit-logs) 각각을 커버.
CREATE INDEX IF NOT EXISTS admin_audit_logs_target_user_idx ON app.admin_audit_logs (target_user_id, at DESC);
CREATE INDEX IF NOT EXISTS admin_audit_logs_admin_idx ON app.admin_audit_logs (admin_id, at DESC);
-- 90일 보존 삭제 배치(at 단독 범위 스캔)용.
CREATE INDEX IF NOT EXISTS admin_audit_logs_at_idx ON app.admin_audit_logs (at);
