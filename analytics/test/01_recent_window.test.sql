-- 윈도우 뷰 기대값. 시드 근거:
--   더미 콘텐츠 4건 (dummy_a 3건 + dummy_b 1건) — 기본 N=12에서 전부 포함
--   dummy_a 최신순: dummy_f1(06-03) > dummy_r2(06-02) > dummy_r1(06-01)
--   N=1이면 계정별 최신 1건만: dummy_f1, dummy_r3
-- 결정적 실행을 위해 키를 기본값 상태로 강제
DELETE FROM app_setting WHERE key = 'analytics.recent-window';

DO $$
BEGIN
  -- 기본 N=12: 더미 전부 포함
  ASSERT (SELECT count(*) FROM analytics.v_recent_content) = 4,
    'v_recent_content rows != 4 (default N=12)';
  ASSERT (SELECT count(*) FROM analytics.v_recent_content WHERE owner_username = 'dummy_a') = 3,
    'v_recent_content dummy_a rows != 3';
  -- 최신 게시물이 rank 1
  ASSERT (SELECT short_code FROM analytics.v_recent_content
          WHERE owner_username = 'dummy_a' AND recency_rank = 1) = 'dummy_f1',
    'v_recent_content dummy_a rank1 != dummy_f1';
  -- base 조인으로 지표가 붙는다
  ASSERT (SELECT views FROM analytics.v_recent_content WHERE short_code = 'dummy_r1') = 11000,
    'v_recent_content dummy_r1 views != 11000';
END $$;

-- N=1로 런타임 조정: 계정별 최신 1건만 남는다
INSERT INTO app_setting(key, value) VALUES ('analytics.recent-window', '1');

DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_recent_content) = 2,
    'v_recent_content rows != 2 (N=1)';
  ASSERT (SELECT count(*) FROM analytics.v_recent_content
          WHERE short_code IN ('dummy_f1','dummy_r3')) = 2,
    'v_recent_content N=1 must keep only each account latest';
END $$;
