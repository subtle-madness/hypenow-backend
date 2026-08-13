-- 발굴 표면 성능 개선 (설계 2026-08-13-discovery-stats-matview-design) — 계정별 뷰티 게이트·협찬 수
-- 집계를 물화한다. 기존에는 was 발굴 목록·유사 인플루언서가 요청마다 account_content_series(6.9만)
-- × content_analyses(12.6만) 전 계정 집계를 2회(목록+카운트) 계산해 p95 1.7s(냉캐시 2.2s)였다
-- (08-13 pg_stat_statements 실측). 비용이 content_analyses 행 수에 비례해 방치 시 선형 악화.
--
-- 정의는 V45 account_beauty_ratio(뷰티 원시 카운트)에 sponsored_count만 더한 것. 협찬 판정은
-- content_analyses.ad_type='sponsored'(캡션 분류 정본, 07-17) — account_summaries.sponsored_count
-- (유료파트너십 태그 기준 옛 정의, 미러 소유)와 다르며 그쪽에 컬럼을 더하지 않는 이유다.
-- 임계값 정책(최소 분석 건수·최소 비율)은 V45와 같은 이유로 여기 두지 않는다 — was가 조회 시 적용.
--
-- 갱신: analytics가 content_analyses 변경 잡(일상 분석·백필·버스트)·미러 종료 시
-- REFRESH MATERIALIZED VIEW CONCURRENTLY로 갱신한다(집계 ~0.4s). CONCURRENTLY가 유니크 인덱스를
-- 요구해 아래 인덱스는 조회 최적화이자 갱신 전제 조건이다.
-- V45 뷰는 expand-contract에 따라 이번 릴리스에 남긴다 — was 참조가 모두 넘어간 다음 릴리스에서 DROP.
CREATE MATERIALIZED VIEW account_discovery_stats AS
SELECT s.account_handle,
       count(*) FILTER (WHERE an.is_beauty IS NOT NULL) AS analyzed_count,
       count(*) FILTER (WHERE an.is_beauty IS TRUE)     AS beauty_count,
       count(*) FILTER (WHERE an.ad_type = 'sponsored') AS sponsored_count
FROM account_content_series s
JOIN content_analyses an ON an.short_code = s.short_code
GROUP BY s.account_handle;

CREATE UNIQUE INDEX account_discovery_stats_handle_key
    ON account_discovery_stats (account_handle);

COMMENT ON MATERIALIZED VIEW account_discovery_stats IS
  '계정별 발굴 표면 집계 스냅샷 — 창 내 분석 완료·뷰티 판정·협찬(ad_type 정본) 건수.
   analytics 잡 종료 시 REFRESH CONCURRENTLY, 임계값 정책은 was가 조회 시 적용(08-13 설계).';
