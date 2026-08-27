-- 6.21 발굴 목록 사전집계 (2026-08-27, 스펙 2026-08-27-discovery-precompute-design.md).
-- 발굴 쿼리가 요청마다 하던 집계 3종(뷰티 비율·카테고리 비중 게이트·협찬 수)을 matview로
-- 내려 캐시 미스 4~11초를 수백 ms로 줄인다. 입력(account_content_series·content_analyses)은
-- 나이트리 잡 체인에서만 변하므로 잡 훅 refresh(DerivedViewRefresher)로 신선도 손실 없음.
-- unique index는 REFRESH CONCURRENTLY 필수 조건.

-- allow-destructive: 같은 이름·컬럼의 materialized view로 즉시 재생성 (사전집계 전환, V45 대체)
DROP VIEW account_beauty_ratio;
CREATE MATERIALIZED VIEW account_beauty_ratio AS
SELECT s.account_handle,
       count(*) FILTER (WHERE an.is_beauty IS NOT NULL) AS analyzed_count,
       count(*) FILTER (WHERE an.is_beauty IS TRUE)     AS beauty_count
FROM account_content_series s
JOIN content_analyses an ON an.short_code = s.short_code
GROUP BY s.account_handle
WITH DATA;
CREATE UNIQUE INDEX ux_account_beauty_ratio ON account_beauty_ratio (account_handle);
COMMENT ON MATERIALIZED VIEW account_beauty_ratio IS
  '계정별 뷰티 게시물 비율 원시 카운트(V45 뷰의 matview 전환) — 임계값 정책은 was가 적용.
   갱신: analytics DerivedViewRefresher(입력 변경 잡 후크).';

-- 계정×대분류 비중 — 발굴 게이트(V1InfluencerDiscoveryRepository mainCategory ≥20)와
-- 동일 분모(is_beauty IS TRUE AND main_category IS NOT NULL 창 내 게시물)·동일 round 산식.
-- 게이트 원식 round(100.0*count FILTER(main=:mc)/count(*))에서 :mc 0건이면 0(≥20 false)
-- ↔ 여기선 행 부재(EXISTS false) — 결과 동치.
CREATE MATERIALIZED VIEW account_category_share AS
SELECT s.account_handle, an.main_category,
       round(100.0 * count(*) / sum(count(*)) OVER (PARTITION BY s.account_handle))::int AS pct
FROM account_content_series s
JOIN content_analyses an ON an.short_code = s.short_code
WHERE an.is_beauty IS TRUE AND an.main_category IS NOT NULL
GROUP BY s.account_handle, an.main_category
WITH DATA;
CREATE UNIQUE INDEX ux_account_category_share
    ON account_category_share (account_handle, main_category);
COMMENT ON MATERIALIZED VIEW account_category_share IS
  '계정×대분류(slug) 비중 — 발굴 mainCategory 게이트 전용 사전집계. 카드 표시용 믹스는
   account_category_stats(라벨 기준)가 따로 있다. 갱신: DerivedViewRefresher.';

CREATE MATERIALIZED VIEW account_sponsored_counts AS
SELECT s.account_handle, count(*) AS cnt
FROM account_content_series s
JOIN content_analyses an ON an.short_code = s.short_code AND an.ad_type = 'sponsored'
GROUP BY s.account_handle
WITH DATA;
CREATE UNIQUE INDEX ux_account_sponsored_counts ON account_sponsored_counts (account_handle);
COMMENT ON MATERIALIZED VIEW account_sponsored_counts IS
  '계정별 협찬(ad_type=sponsored) 게시물 수 — 발굴 목록 sp 조인 사전집계. 갱신: DerivedViewRefresher.';
