-- base 뷰 기대값 (신스키마). 시드 근거:
--   dummy_a 프로필 최신 followers=5500 (06-01 HIKER_MOBILE 5000 → 06-05 SELF_GQL 5500)
--   9909101 최신 상세 likes=520, views=11000 (06-04 페이지는 구지표 500/10000)
--   9909102 views=7000 (play_count 없음 → ig_play_count 폴백)
--   9909103 피드(media_type 1) → views NULL
--   9909101 최신 페이지(06-06)는 image_versions2 누락 → 썸네일은 직전 페이지(06-05) 값 폴백
--   9909101 댓글 3건, like_count = {7, NULL, 2}

-- 소스 3형태 커버 보강: 시드의 최신 스냅샷은 SELF_GQL(dummy_a)·DATALIKERS(dummy_b)라
-- HIKER_MOBILE 경로가 뷰 출력에 안 잡힘 — 전체 키를 담은 HIKER_MOBILE 픽스처를 추가한다.
INSERT INTO influencer(id, username) VALUES (9909003,'dummy_c');
INSERT INTO raw_profile(influencer_id, crawl_run_id, source, username, followers, payload, captured_at) VALUES
 (9909003,9909990,'HIKER_MOBILE','dummy_c',7000,
  '{"user":{"username":"dummy_c","full_name":"더미 씨","profile_pic_url":"https://pic/c.jpg","hd_profile_pic_url_info":{"url":"https://pic/c_hd.jpg"},"following_count":321,"media_count":77,"biography":"hiker bio","external_url":"https://link.example/c","follower_count":7000}}'::jsonb,
  timestamptz '2026-06-05 00:00:00+09');

DO $$
BEGIN
  -- v_base_profile: 계정별 1행, 최신 스냅샷 선택
  ASSERT (SELECT count(*) FROM analytics.v_base_profile WHERE username LIKE 'dummy_%') = 3,
    'v_base_profile dummy rows != 3';
  ASSERT (SELECT followers FROM analytics.v_base_profile WHERE username = 'dummy_a') = 5500,
    'v_base_profile dummy_a followers != 5500 (latest snapshot)';

  -- 프로필 payload 3경로: SELF_GQL {data,user,*} — dummy_a 최신
  ASSERT (SELECT display_name FROM analytics.v_base_profile WHERE username = 'dummy_a') = '더미 에이',
    'v_base_profile dummy_a display_name != 더미 에이 (SELF_GQL 경로)';
  ASSERT (SELECT profile_image_url FROM analytics.v_base_profile WHERE username = 'dummy_a') = 'https://pic/a.jpg',
    'v_base_profile dummy_a profile_image_url != latest a.jpg (SELF_GQL 경로)';
  ASSERT (SELECT external_link FROM analytics.v_base_profile WHERE username = 'dummy_a') = 'https://link.example/a',
    'v_base_profile dummy_a external_link != https://link.example/a';
  -- SELF_GQL payload에 없는 키 → NULL (키 있는 케이스는 dummy_c·10번 테스트 픽스처가 검증)
  ASSERT (SELECT follows_count FROM analytics.v_base_profile WHERE username = 'dummy_a') IS NULL,
    'v_base_profile dummy_a follows_count not null (payload has no key)';
  ASSERT (SELECT posts_count FROM analytics.v_base_profile WHERE username = 'dummy_a') IS NULL,
    'v_base_profile dummy_a posts_count not null (payload has no key)';
  ASSERT (SELECT biography FROM analytics.v_base_profile WHERE username = 'dummy_a') IS NULL,
    'v_base_profile dummy_a biography not null (payload has no key)';

  -- 프로필 payload 3경로: DATALIKERS 평탄 — dummy_b
  ASSERT (SELECT display_name FROM analytics.v_base_profile WHERE username = 'dummy_b') = '더미 비',
    'v_base_profile dummy_b display_name != 더미 비 (DATALIKERS 평탄 경로)';
  ASSERT (SELECT profile_image_url FROM analytics.v_base_profile WHERE username = 'dummy_b') = 'https://pic/b.jpg',
    'v_base_profile dummy_b profile_image_url != b.jpg (DATALIKERS 평탄 경로)';
  ASSERT (SELECT external_link FROM analytics.v_base_profile WHERE username = 'dummy_b') IS NULL,
    'v_base_profile dummy_b external_link not null (payload has no key)';

  -- 프로필 payload 3경로: HIKER_MOBILE {user,*} — 픽스처 dummy_c (전체 키)
  ASSERT (SELECT display_name FROM analytics.v_base_profile WHERE username = 'dummy_c') = '더미 씨',
    'v_base_profile dummy_c display_name != 더미 씨 (HIKER_MOBILE 경로)';
  ASSERT (SELECT profile_image_url FROM analytics.v_base_profile WHERE username = 'dummy_c') = 'https://pic/c.jpg',
    'v_base_profile dummy_c profile_image_url != c.jpg (profile_pic_url이 hd보다 우선)';
  ASSERT (SELECT follows_count FROM analytics.v_base_profile WHERE username = 'dummy_c') = 321,
    'v_base_profile dummy_c follows_count != 321 (HIKER_MOBILE following_count)';
  ASSERT (SELECT posts_count FROM analytics.v_base_profile WHERE username = 'dummy_c') = 77,
    'v_base_profile dummy_c posts_count != 77 (HIKER_MOBILE media_count)';
  ASSERT (SELECT biography FROM analytics.v_base_profile WHERE username = 'dummy_c') = 'hiker bio',
    'v_base_profile dummy_c biography != hiker bio';
  ASSERT (SELECT external_link FROM analytics.v_base_profile WHERE username = 'dummy_c') = 'https://link.example/c',
    'v_base_profile dummy_c external_link != https://link.example/c';

  -- v_base_detail: 콘텐츠별 1행, 최신 페이지 아이템 + 조회수 폴백
  ASSERT (SELECT count(*) FROM analytics.v_base_detail WHERE content_id BETWEEN 9909101 AND 9909104) = 4,
    'v_base_detail dummy rows != 4';
  ASSERT (SELECT likes FROM analytics.v_base_detail WHERE content_id = 9909101) = 520,
    'v_base_detail 9909101 likes != 520 (latest page)';
  ASSERT (SELECT views FROM analytics.v_base_detail WHERE content_id = 9909101) = 11000,
    'v_base_detail 9909101 views != 11000';
  ASSERT (SELECT views FROM analytics.v_base_detail WHERE content_id = 9909102) = 7000,
    'v_base_detail 9909102 views != 7000 (ig_play_count fallback)';
  ASSERT (SELECT views FROM analytics.v_base_detail WHERE content_id = 9909103) IS NULL,
    'v_base_detail 9909103 views is not NULL (feed)';
  ASSERT (SELECT caption FROM analytics.v_base_detail WHERE content_id = 9909101) = 'cap r1',
    'v_base_detail 9909101 caption != cap r1 (latest page)';
  -- media_type 숫자 → 구 Apify 어휘 매핑 (1 Image / 2 Video)
  ASSERT (SELECT media_type FROM analytics.v_base_detail WHERE content_id = 9909101) = 'Video',
    'v_base_detail 9909101 media_type != Video (media_type 2 매핑)';
  ASSERT (SELECT media_type FROM analytics.v_base_detail WHERE content_id = 9909103) = 'Image',
    'v_base_detail 9909103 media_type != Image (media_type 1 매핑)';

  -- 썸네일 non-null 폴백: 최신 페이지(06-06)에 image_versions2 누락 →
  -- 직전 페이지(06-05)의 값이 살아남아야 한다 (최신 고정이면 NULL 퇴행 — 실측 DZjhKALAgx1)
  ASSERT (SELECT thumbnail_url FROM analytics.v_base_detail WHERE content_id = 9909101) = 'https://thumb/dummy_r1.jpg',
    'v_base_detail 9909101 thumbnail_url != non-null 폴백 dummy_r1.jpg';
  ASSERT (SELECT thumbnail_url FROM analytics.v_base_detail WHERE content_id = 9909102) = 'https://thumb/dummy_r2.jpg',
    'v_base_detail 9909102 thumbnail_url != dummy_r2.jpg';
  ASSERT (SELECT original_url FROM analytics.v_base_detail WHERE content_id = 9909101) = 'https://www.instagram.com/p/dummy_r1/',
    'v_base_detail 9909101 original_url mismatch (code 합성)';

  -- v_base_content: 콘텐츠 메타 노출 — 카테고리·광고 컬럼은 개편으로 소멸, NULL/false 상수 계약
  ASSERT (SELECT count(*) FROM analytics.v_base_content WHERE short_code LIKE 'dummy_%') = 4,
    'v_base_content dummy rows != 4';
  ASSERT (SELECT category_id FROM analytics.v_base_content WHERE short_code = 'dummy_r1') IS NULL,
    'v_base_content category_id != NULL (신스키마 상수 계약)';
  ASSERT (SELECT ad_marked FROM analytics.v_base_content WHERE short_code = 'dummy_f1') = false,
    'v_base_content ad_marked != false (신스키마 상수 계약)';

  -- v_base_comment: 평탄화 + like_count 추출 (댓글은 구 수집분 그대로 — 테이블·payload 불변)
  ASSERT (SELECT count(*) FROM analytics.v_base_comment WHERE content_id = 9909101) = 3,
    'v_base_comment 9909101 rows != 3';
  ASSERT (SELECT max(like_count) FROM analytics.v_base_comment WHERE content_id = 9909101) = 7,
    'v_base_comment 9909101 max like_count != 7';

  -- 스냅샷 이력: 페이지 × 아이템 평탄화 — 9909101은 페이지 3개에 실려 3행, 구페이지 views=10000
  ASSERT (SELECT count(*) FROM analytics.v_base_detail_history WHERE content_id = 9909101) = 3,
    'v_base_detail_history 9909101 rows != 3';
  ASSERT (SELECT views FROM analytics.v_base_detail_history
          WHERE content_id = 9909101 ORDER BY captured_at ASC LIMIT 1) = 10000,
    'v_base_detail_history 9909101 oldest views != 10000';
  ASSERT (SELECT views FROM analytics.v_base_detail_history WHERE content_id = 9909102) = 7000,
    'v_base_detail_history 9909102 ig_play_count fallback != 7000';
  -- 합성 id(페이지 id × 100000 + 페이지 내 순번)는 전 행 유일해야 한다 (미러 자연키)
  ASSERT (SELECT count(*) FROM analytics.v_base_detail_history WHERE content_id BETWEEN 9909101 AND 9909104)
         = (SELECT count(DISTINCT id) FROM analytics.v_base_detail_history WHERE content_id BETWEEN 9909101 AND 9909104),
    'v_base_detail_history 합성 id 충돌';
END $$;
