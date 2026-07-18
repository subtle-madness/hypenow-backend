-- base 뷰 기대값. 시드 근거: seed/dummy.sql 시나리오 주석.
DO $$
BEGIN
  -- v_base_influencer: 판정 컬럼 노출
  ASSERT (SELECT count(*) FROM analytics.v_base_influencer WHERE username LIKE 'dummy_%') = 5,
    'v_base_influencer dummy rows != 5';
  ASSERT (SELECT beauty FROM analytics.v_base_influencer WHERE username = 'dummy_a') = true,
    'v_base_influencer dummy_a beauty != true';
  ASSERT (SELECT beauty_company FROM analytics.v_base_influencer WHERE username = 'dummy_co') = true,
    'v_base_influencer dummy_co beauty_company != true';

  -- v_base_profile: 계정별 최신 1건 + 소스 분기
  ASSERT (SELECT count(*) FROM analytics.v_base_profile WHERE username LIKE 'dummy_%') = 4,
    'v_base_profile dummy rows != 4 (e는 프로필 없음)';
  ASSERT (SELECT followers FROM analytics.v_base_profile WHERE username = 'dummy_a') = 5500,
    'v_base_profile dummy_a followers != 5500 (최신 SELF_GQL 실컬럼)';
  ASSERT (SELECT display_name FROM analytics.v_base_profile WHERE username = 'dummy_a') = '더미 에이',
    'v_base_profile dummy_a display_name != 더미 에이 (data.user 경로)';
  ASSERT (SELECT profile_image_url FROM analytics.v_base_profile WHERE username = 'dummy_a') = 'https://pic/a_hd.jpg',
    'v_base_profile dummy_a image != a_hd.jpg (profile_pic_url_hd 우선)';
  ASSERT (SELECT follows_count FROM analytics.v_base_profile WHERE username = 'dummy_a') = 120,
    'v_base_profile dummy_a follows_count != 120 (edge_follow.count)';
  ASSERT (SELECT posts_count FROM analytics.v_base_profile WHERE username = 'dummy_a') = 42,
    'v_base_profile dummy_a posts_count != 42 (edge_owner_to_timeline_media.count)';
  ASSERT (SELECT biography FROM analytics.v_base_profile WHERE username = 'dummy_a') = 'bio a',
    'v_base_profile dummy_a biography != bio a';
  ASSERT (SELECT external_link FROM analytics.v_base_profile WHERE username = 'dummy_a') = 'https://link.example/a',
    'v_base_profile dummy_a external_link mismatch';
  ASSERT (SELECT display_name FROM analytics.v_base_profile WHERE username = 'dummy_b') = '더미 비',
    'v_base_profile dummy_b display_name != 더미 비 (HIKER_MOBILE user 래퍼 경로)';
  ASSERT (SELECT follows_count FROM analytics.v_base_profile WHERE username = 'dummy_b') = 200,
    'v_base_profile dummy_b follows_count != 200 (following_count)';
  ASSERT (SELECT posts_count FROM analytics.v_base_profile WHERE username = 'dummy_b') = 10,
    'v_base_profile dummy_b posts_count != 10 (media_count)';
  ASSERT (SELECT external_link FROM analytics.v_base_profile WHERE username = 'dummy_b') IS NULL,
    'v_base_profile dummy_b external_link not null (키 없음)';

  -- v_base_reel_item: clips 아이템 평탄화
  ASSERT (SELECT count(*) FROM analytics.v_base_reel_item WHERE short_code LIKE 'dummy_%') = 7,
    'v_base_reel_item dummy rows != 7 (r1x3 + rn + r3 + r4 + r5)';
  ASSERT (SELECT views FROM analytics.v_base_reel_item WHERE short_code = 'dummy_rn') = 100,
    'v_base_reel_item rn views != 100 (ig_play_count 폴백)';
  ASSERT (SELECT paid_partnership FROM analytics.v_base_reel_item WHERE short_code = 'dummy_r3') = true,
    'v_base_reel_item r3 paid_partnership != true';
  ASSERT (SELECT caption FROM analytics.v_base_reel_item WHERE short_code = 'dummy_r3') IS NULL,
    'v_base_reel_item r3 caption not null (캡션 결측)';

  -- v_base_timeline_item: 타임라인 노드 평탄화
  ASSERT (SELECT count(*) FROM analytics.v_base_timeline_item WHERE short_code LIKE 'dummy_%') = 4,
    'v_base_timeline_item dummy rows != 4';
  ASSERT (SELECT views FROM analytics.v_base_timeline_item WHERE short_code = 'dummy_r1') IS NULL,
    'v_base_timeline_item r1 views not null (video_view_count 0 → NULL)';
  ASSERT (SELECT views FROM analytics.v_base_timeline_item WHERE short_code = 'dummy_r2') = 8000,
    'v_base_timeline_item r2 views != 8000';
  ASSERT (SELECT likes FROM analytics.v_base_timeline_item WHERE short_code = 'dummy_f1') = 2000,
    'v_base_timeline_item f1 likes != 2000 (edge_liked_by 폴백)';
  ASSERT (SELECT views FROM analytics.v_base_timeline_item WHERE short_code = 'dummy_f1') = 999,
    'v_base_timeline_item f1 views != 999 (아이템 층은 원값 — FEED 게이트는 스냅샷 층)';

  -- v_base_content_snapshot: UNION + content_type 게이트 + 합성 id 유일성
  ASSERT (SELECT count(*) FROM analytics.v_base_content_snapshot WHERE content_id BETWEEN 99990101 AND 99990108) = 11,
    'v_base_content_snapshot dummy rows != 11 (r1:4, r2·f1·d1·rn·r3·r4·r5:1)';
  ASSERT (SELECT views FROM analytics.v_base_content_snapshot WHERE content_id = 99990103) IS NULL,
    'v_base_content_snapshot f1 views not null (FEED → 무조건 NULL)';
  ASSERT (SELECT count(*) = count(DISTINCT id) FROM analytics.v_base_content_snapshot),
    'v_base_content_snapshot 합성 id 중복';

  -- v_base_detail: 콘텐츠별 최신 1건
  ASSERT (SELECT likes FROM analytics.v_base_detail WHERE content_id = 99990101) = 530,
    'v_base_detail r1 likes != 530 (최신 06-08 스냅샷)';
  ASSERT (SELECT views FROM analytics.v_base_detail WHERE content_id = 99990101) = 12000,
    'v_base_detail r1 views != 12000';
  ASSERT (SELECT caption FROM analytics.v_base_detail WHERE content_id = 99990101) = 'cap r1 v3',
    'v_base_detail r1 caption != cap r1 v3';
  ASSERT (SELECT thumbnail_url FROM analytics.v_base_detail WHERE content_id = 99990101) = 'https://thumb/r1_v3.jpg',
    'v_base_detail r1 thumbnail mismatch';
  ASSERT (SELECT views FROM analytics.v_base_detail WHERE content_id = 99990102) = 8000,
    'v_base_detail r2 views != 8000 (타임라인 전용 릴스)';

  -- v_base_comment
  ASSERT (SELECT count(*) FROM analytics.v_base_comment WHERE content_id = 99990101) = 3,
    'v_base_comment 99990101 rows != 3';
  ASSERT (SELECT max(like_count) FROM analytics.v_base_comment WHERE content_id = 99990101) = 7,
    'v_base_comment 99990101 max like_count != 7';
END $$;
