-- 더미데이터가 정상 적재되는지 + 실데이터 격리가 되는지 확인
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM content WHERE category_id = 999) = 5, 'dummy content != 5';
  ASSERT (SELECT count(*) FROM raw_post_detail) = 5, 'detail rows should be dummy-only 5';
  ASSERT (SELECT count(*) FROM raw_comment WHERE content_id = 9101) = 3, 'c1 comments != 3';
END $$;
