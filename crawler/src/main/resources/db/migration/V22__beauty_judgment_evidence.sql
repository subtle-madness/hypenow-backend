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
-- 판정에 캡션이 0건이었는지는 "판정 당시 사용된 raw_profile의 source"로 판별해야 한다.
-- CollectJob이 beauty=true 계정 프로필을 주기적으로 재수집하므로, 서브쿼리를 전역 최신
-- raw_profile로 잡으면 판정 이후 다른 소스로 재수집된 계정은 이 EXISTS가 false가 되어
-- beauty_caption_count가 NULL로 남는다 — 하필 그 대상이 이 마이그레이션으로 고치려는
-- 오판 계정(beauty=true)이라 조용히 재판정 대상에서 누락된다. 그래서 "판정 시점(beauty_judged_at)
-- 이전에 캡처된 것 중 최신"으로 제한한다. beauty_judged_at이 NULL인 판정분(기록 이전 레거시)은
-- 판정 시점을 알 수 없으니 종전대로 전역 최신을 본다.
update influencer i set beauty_caption_count = 0
where i.beauty_class is not null
  and i.beauty_source = 'CLAUDE'
  and exists (
    select 1 from raw_profile rp
    where rp.influencer_id = i.id
      and rp.source in ('HIKER_MOBILE', 'DATALIKERS')
      and rp.captured_at = (select max(rp2.captured_at) from raw_profile rp2
                            where rp2.influencer_id = i.id
                              and (i.beauty_judged_at is null or rp2.captured_at <= i.beauty_judged_at))
  );
