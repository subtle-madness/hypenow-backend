-- 랜딩 통계 기대값. 모수 = 뷰티 인플루언서 ∩ 마이크로(3천~5만): a(5500)·b(20000).
-- co(8000)는 구간 안이지만 회사, x(9000)는 비뷰티 — 제외 검증.
-- 콘텐츠 = a·b의 ENUMERATION(스냅샷 보유): r1·r2·f1·rn·r3 = 5. d1(DISCOVERY) 제외.
-- 조회수(릴스만, 최신 스냅샷): 12000+8000+100+40000 = 60100, avg = 15025.
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_landing_stats) = 1, 'landing_stats != 1행';
  ASSERT (SELECT influencers_count FROM analytics.v_landing_stats) = 2, 'influencers_count != 2';
  ASSERT (SELECT followers3k10k FROM analytics.v_landing_stats) = 1, 'followers3k10k != 1';
  ASSERT (SELECT followers10k30k FROM analytics.v_landing_stats) = 1, 'followers10k30k != 1';
  ASSERT (SELECT followers30k50k FROM analytics.v_landing_stats) = 0, 'followers30k50k != 0';
  ASSERT (SELECT contents_count FROM analytics.v_landing_stats) = 5, 'contents_count != 5';
  ASSERT (SELECT total_views FROM analytics.v_landing_stats) = 60100, 'total_views != 60100';
  ASSERT (SELECT avg_views FROM analytics.v_landing_stats) = 15025, 'avg_views != 15025';
END $$;
