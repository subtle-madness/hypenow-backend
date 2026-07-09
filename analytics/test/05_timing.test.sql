DO $$
BEGIN
  -- KST 기준 오전 9시 업로드 = c1(09:00), c3(09:00), c5(09:30) = 3건
  ASSERT (SELECT sum(content_count) FROM analytics.v_timing_performance WHERE hour = 9) = 3, 'hour=9 count != 3';
  -- 월요일(2026-06-01)에 c1,c2,c5 = 3건 (isodow 1)
  ASSERT (SELECT sum(content_count) FROM analytics.v_timing_performance WHERE dow = 1) = 3, 'monday count != 3';
END $$;
