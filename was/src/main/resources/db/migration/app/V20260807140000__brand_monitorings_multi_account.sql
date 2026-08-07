-- 브랜드 다계정 연결(2026-08-07 FE 요청) — 활성 연결 "유저당 1개" → "유저·브랜드당 1개"로 완화.
-- 유저별 계정 수 한도(10개)는 앱(BrandLinkTransaction)이 유저 행 FOR UPDATE 직렬화로 강제한다.
-- 새 유니크를 먼저 만들고 옛 유니크를 지운다 — 기존 데이터는 유저당 최대 1행이라 둘 다 만족하고,
-- 롤링 중 구코드도 앱 레벨 사전 409로 여전히 1개만 만들므로 어느 순간에도 위반이 없다.
CREATE UNIQUE INDEX brand_monitorings_active_user_brand_uidx
    ON app.brand_monitorings (user_id, brand_id) WHERE deleted_at IS NULL;
-- user_id 단독 조회는 새 복합 인덱스의 선두 컬럼이 커버한다.
DROP INDEX app.brand_monitorings_active_user_uidx;

-- users.instgram_account_name(계정명 불변 계약)은 다계정 전환으로 용도 소멸 — 코드에서 읽기·쓰기를
-- 모두 제거했다. 컬럼 제거는 expand-contract 규칙대로 참조 코드가 완전히 사라진 다음 릴리스에서.
