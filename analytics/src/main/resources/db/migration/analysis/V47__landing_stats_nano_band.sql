-- 랜딩 통계 모수 확장(하한 3,000→500 — 나노 포함): 500~3천 구간 계정 수 컬럼 추가.
-- expand 단계 — 끝에 붙는 ADD COLUMN이라 record 컴포넌트도 마지막(updatedAt 뒤)이 정본(§4-3).
-- 값은 다음 미러 실행까지 NULL — was 조회가 COALESCE(...,0)으로 방어한다.
ALTER TABLE landing_stats ADD COLUMN followers500to3k bigint;
