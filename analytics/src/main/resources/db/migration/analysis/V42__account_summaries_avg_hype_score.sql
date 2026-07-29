-- 계정 하입 스코어(스펙 2026-07-29-influencer-avg-hype-score): 최근창 콘텐츠 hype_score 단순 평균(0~100).
-- 산식 정본은 analytics 뷰(10_account_detail.sql v_account_summaries) — 여기는 미러 저장 형상만.
ALTER TABLE account_summaries ADD COLUMN avg_hype_score bigint;
