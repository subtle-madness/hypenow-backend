-- 최근 N개 윈도우 + 서빙 모수(뷰티 인플루언서 ∩ ENUMERATION) 기대값.
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_recent_content WHERE owner_username LIKE 'dummy_%') = 6,
    'v_recent_content dummy rows != 6 (a:r1·r2·f1·rn + b:r3 + ra1(액터))';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_recent_content
                     WHERE short_code IN ('dummy_d1','dummy_r4','dummy_r5')),
    'v_recent_content에 제외 대상 존재 (DISCOVERY·회사·비뷰티)';
  ASSERT (SELECT recency_rank FROM analytics.v_recent_content WHERE short_code = 'dummy_rn') = 1,
    'v_recent_content rn recency_rank != 1 (최신 업로드)';
  ASSERT (SELECT recency_rank FROM analytics.v_recent_content WHERE short_code = 'dummy_r1') = 4,
    'v_recent_content r1 recency_rank != 4';
  ASSERT (SELECT ad_marked FROM analytics.v_recent_content WHERE short_code = 'dummy_r3') = true,
    'v_recent_content r3 ad_marked != true (is_paid_partnership)';
  ASSERT (SELECT ad_marked FROM analytics.v_recent_content WHERE short_code = 'dummy_r1') = false,
    'v_recent_content r1 ad_marked != false';
END $$;

-- 윈도우 컷: N=2로 줄이면 dummy_a는 최신 2개(rn·f1)만 남는다.
INSERT INTO app_setting(key, value) VALUES ('analytics.recent-window', '2');
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_recent_content WHERE owner_username = 'dummy_a') = 2,
    'v_recent_content 윈도우 N=2 미적용';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_recent_content WHERE short_code = 'dummy_r1'),
    'v_recent_content N=2인데 r1(rank 4) 잔존';
END $$;
DELETE FROM app_setting WHERE key = 'analytics.recent-window';
