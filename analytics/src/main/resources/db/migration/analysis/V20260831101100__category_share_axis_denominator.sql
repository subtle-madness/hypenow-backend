-- 발굴 비중 게이트 사전집계를 축별 분모로 재정의 (2026-08-31 F&B 서빙 개방 §3).
--
-- 구 정의는 WHERE is_beauty IS TRUE라 F&B 분류 행이 아예 없어 F&B 필터가 항상 빈 결과였다.
-- 신 정의는 분모를 계정×축으로 파티션한다 — 뷰티 행의 pct는 분모가 "그 계정의 뷰티 분류분"
-- 그대로라 기존과 동치다(불변식: main NOT NULL ∧ axis=beauty ⟺ is_beauty=true ∧ main NOT NULL,
-- V34 백필로 과거분 포함 전역 성립). F&B 행이 새로 생기고 분모는 F&B 분류분 — 사용자 확정
-- "F&B 비중 20% 게이트 대칭"의 재료.
--
-- matview는 CREATE OR REPLACE가 없어 DROP 후 재생성한다. 유니크 인덱스(handle, main_category)는
-- 대분류가 축을 결정하므로 그대로 유효 — REFRESH CONCURRENTLY(DerivedViewRefresher) 유지.
-- 롤링 창: 구 was 쿼리는 (account_handle, main_category, pct)만 SELECT하므로 무해.
-- allow-destructive: matview 재정의 — DROP 직후 같은 마이그레이션 트랜잭션 안에서 재생성해
--   참조 공백이 없고, 소비자(was 발굴 게이트)와 같은 릴리스로 나간다
DROP MATERIALIZED VIEW account_category_share;
CREATE MATERIALIZED VIEW account_category_share AS
SELECT s.account_handle, an.main_category,
       round(100.0 * count(*)
             / sum(count(*)) OVER (PARTITION BY s.account_handle, t.axis))::int AS pct
FROM account_content_series s
JOIN content_analyses an ON an.short_code = s.short_code
JOIN (SELECT DISTINCT main_value, axis FROM beauty_taxonomy) t ON t.main_value = an.main_category
WHERE an.main_category IS NOT NULL
GROUP BY s.account_handle, an.main_category, t.axis
WITH DATA;
CREATE UNIQUE INDEX ux_account_category_share
    ON account_category_share (account_handle, main_category);
COMMENT ON MATERIALIZED VIEW account_category_share IS
  '계정×대분류(slug) 비중 — 발굴 mainCategory 게이트 전용 사전집계. 분모는 계정×축(2026-08-31).
   카드 표시용 믹스는 account_category_stats(라벨·뷰티 게이트)가 따로 있다. 갱신: DerivedViewRefresher.';
