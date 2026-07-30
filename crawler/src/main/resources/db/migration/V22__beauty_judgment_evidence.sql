-- 판정 근거 기록 — 캡션 0건으로 판정된 계정을 나중에 쌓인 실측 캡션으로 재판정하기 위한 대상 표시.
-- (2026-07-30 실측: 서빙 뷰티 인플루언서 7,095 중 게시물 뷰티 비율 0%인 886개를 스팟체크했더니 85%가 오판)
alter table influencer add column beauty_caption_count smallint;
alter table influencer add column beauty_basis text;

-- basis는 LLM이 밝힌 판정 주근거. CATEGORY_ONLY는 인스타그램 자기신고 category만 보고 판단한 저확신 판정.
alter table influencer add constraint influencer_beauty_basis_check
    check (beauty_basis in ('CAPTION', 'BIO', 'CATEGORY_ONLY'));

-- 기록 이전 판정분 백필 — 프로필 응답에 게시물이 아예 없는 소스(HIKER_MOBILE·DATALIKERS)로 판정된
-- 계정은 캡션이 구조적으로 0건이었다. 0으로 표시해야 후속 재판정 선정 쿼리에 걸린다.
-- NULL로 두면 "기록 이전 판정분"이라 재판정 대상에서 빠진다 — 오판 886개가 여기 포함된다.
update influencer i set beauty_caption_count = 0
where i.beauty_class is not null
  and i.beauty_source = 'CLAUDE'
  and exists (
    select 1 from raw_profile rp
    where rp.influencer_id = i.id
      and rp.source in ('HIKER_MOBILE', 'DATALIKERS')
      and rp.captured_at = (select max(rp2.captured_at) from raw_profile rp2
                            where rp2.influencer_id = i.id)
  );
