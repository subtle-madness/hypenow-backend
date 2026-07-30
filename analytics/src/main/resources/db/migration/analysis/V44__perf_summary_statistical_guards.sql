-- 성과 요약 통계 왜곡 가드(스펙 2026-07-30-perf-summary-statistical-guards-design.md §3-1·§4).
-- 산식 정본은 analytics 뷰(10_account_detail.sql v_account_summaries) — 여기는 미러 저장 형상만.
-- 컬럼 이름·순서 = 뷰 SELECT 끝 9개 = AccountSummary record 끝 9개(FlywaySchemaTest 대조).
ALTER TABLE account_summaries
  ADD COLUMN views_sample_count    int,
  ADD COLUMN likes_sample_count    int,
  ADD COLUMN comments_sample_count int,
  ADD COLUMN reels_count           int,
  ADD COLUMN feed_count            int,
  ADD COLUMN median_views          bigint,
  ADD COLUMN median_er_pct         numeric,
  ADD COLUMN top_views_share_pct   int,
  ADD COLUMN window_span_days      int;

-- 재생성 게이트(§4): perf_summary 등 계정 문구의 판정 근거가 바뀌므로(avg→median 등) 기존 이력을
-- 낡음으로 표시할 버전 컬럼. ELIGIBLE_WHERE에 `OR latest.copy_version < CopyRules.VERSION` 조건이 붙는다.
ALTER TABLE account_analyses ADD COLUMN copy_version int NOT NULL DEFAULT 0;
