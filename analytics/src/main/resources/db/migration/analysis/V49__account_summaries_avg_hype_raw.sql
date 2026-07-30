-- 계정 정렬 키 정합(스펙 2026-07-30-hype-score-v3-decay-after-mapping-design.md §9 하위절):
-- avg_hype_score(정수 반올림)가 상위권에서 동점을 대량 생성해 발굴 목록 정렬이 사실상 handle
-- 알파벳순에 지배되는 문제 — 표시는 avg_hype_score 그대로, 정렬만 반올림 전 raw 평균을 쓴다.
-- 산식 정본은 analytics 뷰(10_account_detail.sql v_account_summaries) — 여기는 미러 저장 형상만.
-- expand-contract: ADD COLUMN만(파괴적 아님) — 맨 끝(email 뒤)에 붙는다. CREATE OR REPLACE VIEW가
-- 기존 컬럼 사이에 끼워 넣기를 지원하지 않아(뷰·DDL·record 세 곳 모두 맨 끝 추가로 통일) —
-- record 위치(AccountSummary 마지막 필드, email 다음)와 순서 일치 필수.
ALTER TABLE account_summaries ADD COLUMN avg_hype_raw numeric;
