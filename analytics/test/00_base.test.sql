-- base 뷰 기대값. 시드 근거:
--   dummy_a 프로필 최신 followers=5500 (06-01 5000 → 06-05 5500)
--   9101 최신 상세 likes=520, views=11000 (06-04 스냅샷은 구버전)
--   9102 views=7000 (videoPlayCount 없음 → videoViewCount 폴백)
--   9103 피드 → views NULL
--   9101 댓글 3건, like_count = {7, NULL, 2}
DO $$
BEGIN
  -- v_base_profile: 계정별 1행, 최신 스냅샷 선택
  ASSERT (SELECT count(*) FROM analytics.v_base_profile WHERE username LIKE 'dummy_%') = 2,
    'v_base_profile dummy rows != 2';
  ASSERT (SELECT followers FROM analytics.v_base_profile WHERE username = 'dummy_a') = 5500,
    'v_base_profile dummy_a followers != 5500 (latest snapshot)';

  -- v_base_detail: 콘텐츠별 1행, 최신 스냅샷 + 조회수 폴백
  ASSERT (SELECT count(*) FROM analytics.v_base_detail WHERE content_id BETWEEN 9101 AND 9104) = 4,
    'v_base_detail dummy rows != 4';
  ASSERT (SELECT likes FROM analytics.v_base_detail WHERE content_id = 9101) = 520,
    'v_base_detail 9101 likes != 520 (latest snapshot)';
  ASSERT (SELECT views FROM analytics.v_base_detail WHERE content_id = 9101) = 11000,
    'v_base_detail 9101 views != 11000';
  ASSERT (SELECT views FROM analytics.v_base_detail WHERE content_id = 9102) = 7000,
    'v_base_detail 9102 views != 7000 (videoViewCount fallback)';
  ASSERT (SELECT views FROM analytics.v_base_detail WHERE content_id = 9103) IS NULL,
    'v_base_detail 9103 views is not NULL (feed)';
  ASSERT (SELECT caption FROM analytics.v_base_detail WHERE content_id = 9101) = 'cap r1',
    'v_base_detail 9101 caption != cap r1 (latest snapshot)';

  -- v_base_content: 콘텐츠 메타 노출
  ASSERT (SELECT count(*) FROM analytics.v_base_content WHERE category_id = 999) = 4,
    'v_base_content dummy rows != 4';
  ASSERT (SELECT ad_marked FROM analytics.v_base_content WHERE short_code = 'dummy_f1') = true,
    'v_base_content dummy_f1 ad_marked != true';

  -- v_base_comment: 평탄화 + like_count 추출
  ASSERT (SELECT count(*) FROM analytics.v_base_comment WHERE content_id = 9101) = 3,
    'v_base_comment 9101 rows != 3';
  ASSERT (SELECT max(like_count) FROM analytics.v_base_comment WHERE content_id = 9101) = 7,
    'v_base_comment 9101 max like_count != 7';
END $$;
