-- 하입 스코어 소수점 노출(스펙 2026-07-30-hype-score-v3-decay-after-mapping-design.md §10) —
-- contents.hype_score·account_summaries.avg_hype_score(둘 다 bigint)는 값·의미 불변으로 유지하고,
-- 콘텐츠 출력 매핑까지 반영한 소수 표시값을 신규 컬럼으로 추가한다(expand-contract, ADD COLUMN만).
-- 산식 정본은 analytics 뷰(02_serving.sql analytics.hype_score_output·10_account_detail.sql
-- analytics.hype_account_score_precise) — 여기는 미러 저장 형상만.
-- 신규 Flyway 버전은 UTC 타임스탬프로 채번한다(CLAUDE.md 07-30~) — 기존 V1~V49는 rename 금지.
-- 둘 다 맨 끝에 붙는다: contents는 ad_marked 뒤, account_summaries는 avg_hype_raw 뒤 — CREATE OR
-- REPLACE VIEW가 기존 컬럼 사이에 끼워 넣기를 지원하지 않아(뷰·DDL·record 세 곳 모두 맨 끝 추가로
-- 통일) — record 필드 위치(Content.hypeScorePrecise 마지막, AccountSummary.avgHypeScorePrecise 마지막)와
-- 순서 일치 필수.
ALTER TABLE contents ADD COLUMN hype_score_precise numeric;
ALTER TABLE account_summaries ADD COLUMN avg_hype_score_precise numeric;
