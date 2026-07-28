-- 뷰티 판정 v3 — 한국어 콘텐츠 필터. FOREIGN_INFLUENCER(외국인 뷰티 인플루언서) 분류 추가.
-- beauty=false로 파생되어 시드·수집·서빙 모수에서 자동 제외, 세그먼트로만 보존된다.
alter table influencer drop constraint influencer_beauty_class_check;
alter table influencer add constraint influencer_beauty_class_check
    check (beauty_class in ('INFLUENCER', 'COMPANY', 'BEAUTY_SERVICE', 'FOREIGN_INFLUENCER', 'NOT_BEAUTY'));
-- 기존 INFLUENCER 판정분은 새 기준 재판정 대상 — deploy/scripts/reset-influencer-judgments-v3.sql 참조.
