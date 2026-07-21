-- 계정 카테고리 믹스 복구 (07-21). V8에서 crawler main_group이 사라진 뒤 raw 뷰
-- analytics.v_account_category_stats가 "항상 0행" 상수 스텁으로 남아 미러 테이블도 0행이었다.
-- 대체 소스인 캡션 분류(content_analyses.main_category, V30)는 **analysis DB**에 있어
-- raw DB의 뷰에서는 조인할 수 없다 — 미러(raw 뷰 → analysis 테이블) 경로로는 구조적으로 못 채운다.
--
-- 그래서 미러를 포기하고 **analysis DB 안의 파생 뷰**로 되살린다. 입력(account_content_series·
-- content_analyses·beauty_taxonomy)이 전부 분석 결과라 raw를 볼 필요가 없고, 뷰라서 미러 지연 없이
-- 항상 최신이며 과거 분석분에도 자동 소급된다. 이름·컬럼은 그대로 두어 소비자 3곳
-- (AccountAnalysisJob·ClaudeBurstRunner·was InfluencerDetailRepository)은 무접촉.
--
-- 정의:
--   모수 = 계정별 최근 N개 윈도우(account_content_series — ARCHITECTURE §4-1) ∩ 뷰티 분석분
--   라벨(main_group) = beauty_taxonomy 대분류 한글 라벨(main_label). 어휘 밖 값은 slug 그대로 노출.
--   조회수는 안 쓰므로 "피드 views 항상 NULL" 함정과 무관 — 건수 집계만 한다.
DROP TABLE IF EXISTS account_category_stats;

CREATE VIEW account_category_stats AS
SELECT s.account_handle,
       COALESCE(t.main_label, a.main_category) AS main_group,
       count(*)                                AS content_count
FROM account_content_series s
JOIN content_analyses a ON a.short_code = s.short_code
LEFT JOIN (SELECT DISTINCT main_value, main_label FROM beauty_taxonomy) t
       ON t.main_value = a.main_category
WHERE a.is_beauty IS TRUE
  AND a.main_category IS NOT NULL
GROUP BY s.account_handle, COALESCE(t.main_label, a.main_category);

COMMENT ON VIEW account_category_stats IS
  '계정 카테고리 믹스 — 최근 N개 윈도우 × 캡션 대분류. 미러 아님(analysis DB 파생 뷰, 07-21 V35).';

-- 콘텐츠 상세 카테고리 맥락 3컬럼은 여기서 영구 NULL로 확정한다.
-- main_category는 이 행을 만든 통합 1콜 LLM의 산출물이라, 행을 쓰는 시점에는 아직 카테고리를
-- 모른다(닭-달걀). 게다가 content_analyses는 불변(INSERT-only)이라 나중에 채울 수도 없다.
-- → 카테고리 맥락은 was가 조회 시점에 라이브 계산한다(V1ContentReportRepository.findCategoryContext).
--   조회수 baseline·rank를 라이브 계산으로 옮긴 A2(07-19)와 같은 선례.
COMMENT ON COLUMN content_analyses.category_top_percentile IS
  '사용 안 함(영구 NULL) — was가 조회 시점 라이브 계산으로 대체 (07-21).';
COMMENT ON COLUMN content_analyses.category_avg_views IS
  '사용 안 함(영구 NULL) — was가 조회 시점 라이브 계산으로 대체 (07-21).';
COMMENT ON COLUMN content_analyses.category_sample_size IS
  '사용 안 함(영구 NULL) — was가 조회 시점 라이브 계산으로 대체 (07-21).';
