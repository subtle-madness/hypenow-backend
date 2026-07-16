-- 뷰티 판정 시각 — rejudge 선정을 "오래된 판정 우선"으로 만들어, 실패 배치(옛 시각 유지)가
-- 다음 실행에서 먼저 재시도되게 한다(전체 재실행 불필요).
alter table influencer add column beauty_judged_at timestamptz;
-- 기존 판정분은 현재를 기준점으로 — 이후 실패분만 상대적으로 오래된 시각으로 남는다.
update influencer set beauty_judged_at = now() where beauty is not null;
