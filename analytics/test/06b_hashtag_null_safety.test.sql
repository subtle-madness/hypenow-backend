-- 회귀: hashtags/mentions가 JSON null 또는 비배열이어도 뷰가 깨지지 않는다.
-- 더미 콘텐츠에 이어 null hashtags/mentions를 가진 상세 행을 하나 더 넣고,
-- 집계 뷰가 에러 없이 결과를 내는지 확인한다.
INSERT INTO content(id, short_code, content_type, owner_username, uploaded_at, category_id, discovery_keyword, status, first_seen_at, subcategory, main_group, ad_marked)
VALUES (9106,'dummy_c6','REELS','dummy_micro', timestamptz '2026-06-01 10:00:00+09',999,'makeup','AGGREGATED', timestamptz '2026-06-01 00:00:00+09','makeup_sub','A', false);
INSERT INTO raw_post_detail(content_id, crawl_run_id, payload, captured_at)
VALUES (9106,9990,'{"shortCode":"dummy_c6","type":"Video","likesCount":10,"commentsCount":1,"videoPlayCount":100,"hashtags":null,"mentions":"notanarray"}'::jsonb, timestamptz '2026-06-04 10:00:00+09');
DO $$
BEGIN
  -- 뷰가 에러 없이 실행되고, null/비배열 행은 태그 집계에 기여하지 않는다.
  ASSERT (SELECT count(*) FROM analytics.v_hashtag_performance) >= 1, 'hashtag view broke on null';
  ASSERT (SELECT count(*) FROM analytics.v_mention_performance) >= 0, 'mention view broke on non-array';
  -- makeup 카운트는 여전히 3 (c6는 null이라 기여 안 함)
  ASSERT (SELECT content_count FROM analytics.v_hashtag_performance WHERE tag='makeup') = 3, 'makeup count changed';
END $$;
