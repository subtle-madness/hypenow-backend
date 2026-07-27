-- 리포트 개편(07-27): 단일 summary → 섹션별 3분할(성과/콘텐츠/광고).
-- 이력 테이블(INSERT-only)이라 구 컬럼은 보존 — 과거 행 판독용. 신규 행은 구 카피 컬럼에 NULL을 쓴다.
ALTER TABLE account_analyses ADD COLUMN perf_summary    text;
ALTER TABLE account_analyses ADD COLUMN content_summary text;
ALTER TABLE account_analyses ADD COLUMN ad_summary      text;

COMMENT ON COLUMN account_analyses.summary     IS '구 단일 요약 — 07-27 개편 후 미기록(과거 이력만)';
COMMENT ON COLUMN account_analyses.trend_note  IS '미기록(07-27) — 프론트가 추이 그래프로 대체';
COMMENT ON COLUMN account_analyses.chart_note  IS '미기록(07-27) — 프론트가 게시물별 차트로 대체';
COMMENT ON COLUMN account_analyses.ad_headline IS '미기록(07-27) — was 템플릿 조립으로 대체';
COMMENT ON COLUMN account_analyses.pace_note   IS '미기록(07-27) — 표시 제거';

COMMENT ON COLUMN account_analyses.perf_summary    IS '성과 요약(07-27 신설) — AccountAnalysisJob 배선 전까지 NULL';
COMMENT ON COLUMN account_analyses.content_summary IS '콘텐츠 요약(07-27 신설) — AccountAnalysisJob 배선 전까지 NULL';
COMMENT ON COLUMN account_analyses.ad_summary      IS '광고 요약(07-27 신설) — 광고 진술 근거 없으면 NULL';
