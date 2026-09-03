-- 야간 브랜드 스윕 병렬도 런타임 토글(2026-09-03 스윕 단축) — BrandSweepSettings가 TTL 5초로 읽는다.
--   brand-sweep.brand-concurrency        : BrandSweepJob의 브랜드 루프 병렬도(N)
--   brand-sweep.unenumerated-concurrency : 2단계 unenumerated 단건 재수집의 게시물 콜 병렬도(K)
-- 시드값은 코드 기본값(N=3·K=8)과 동일하다 — 이 시드가 유실돼도 행동이 달라지지 않게 하기 위함이다
-- (기준값은 마이그레이션으로 시드한다는 규약을 지키되, 코드가 단독 정본이어도 동작이 같다).
-- 값은 전용 executor 풀 크기(monitoring.brand.sweep-concurrency / .unenumerated-concurrency)로
-- 클램프된다 — 상향은 재배포, 하향은 런타임이 계약이다.
-- 킬스위치(현행 직렬 복원, 재배포 불필요):
--   UPDATE app_setting SET value = '1' WHERE key IN
--     ('brand-sweep.brand-concurrency', 'brand-sweep.unenumerated-concurrency');
INSERT INTO app_setting (key, value) VALUES
    ('brand-sweep.brand-concurrency', '3'),
    ('brand-sweep.unenumerated-concurrency', '8')
ON CONFLICT (key) DO NOTHING;
