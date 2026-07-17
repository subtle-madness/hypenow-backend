-- 뷰티 판정 3분류 — 뷰티 회사(브랜드·쇼핑몰 등) 구분. 회사는 명단 리스트업만, 수집·유사발굴 제외.
alter table influencer add column beauty_company boolean;
-- 기존 판정분은 일단 인플루언서 취급(false) — collect·reels·similar가 멈추지 않게 하고,
-- 재판정(rejudge)이 회사 계정을 true로 정정한다.
update influencer set beauty_company = false where beauty is not null;
