-- 인플루언서 소개글(biography) 정규식 파싱 이메일 (스펙 2026-07-30-influencer-email-from-bio).
-- 산식 정본은 analytics 뷰(10_account_detail.sql v_account_summaries) — 여기는 미러 저장 형상만.
-- expand-contract: ADD COLUMN만(파괴적 아님) — record 끝(AccountSummary.email)과 순서 일치 필수.
ALTER TABLE account_summaries ADD COLUMN email text;
