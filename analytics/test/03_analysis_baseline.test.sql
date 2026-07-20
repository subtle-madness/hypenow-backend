-- 기준선 기대값. dummy_a 윈도우 = r1(11000)·r2(8000)·f1(NULL)·rn(100) — 지표 핀 기준(v_pinned_metrics).
-- r1은 성숙 최이른 clips(06-05, 11000/520)로 고정 — 랭킹(v_contents)과 동일 스냅샷. captured_at만 최신(메타).
DO $$
BEGIN
  ASSERT (SELECT recent_contents_count FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 4,
    'baseline r1 recent_contents_count != 4';
  ASSERT (SELECT recent_reels_count FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 3,
    'baseline r1 recent_reels_count != 3 (views 있는 릴스: r1·r2·rn)';
  ASSERT (SELECT recent_reels_avg_views FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 6367,
    'baseline r1 recent_reels_avg_views != 6367 (avg(11000,8000,100)=6366.67)';
  ASSERT (SELECT rank_in_recent_reels FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 1,
    'baseline r1 rank != 1';
  ASSERT (SELECT rank_in_recent_reels FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r2') = 2,
    'baseline r2 rank != 2';
  ASSERT (SELECT recent12_avg_like_count FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') = 706,
    'baseline r1 avg_like != 706 (avg(520,300,2000,5)=706.25)';
  ASSERT (SELECT category_top_percentile FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') IS NULL,
    'baseline category_top_percentile not null (소스 소멸 — NULL 상수)';
  ASSERT (SELECT category_avg_views FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') IS NULL,
    'baseline category_avg_views not null';
  ASSERT (SELECT category_sample_size FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1') IS NULL,
    'baseline category_sample_size not null';
  ASSERT (SELECT captured_at FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1')
         = timestamptz '2026-06-08 12:00:00+09',
    'baseline r1 captured_at != 최신 스냅샷 시각';

  -- 계정 뷰(v_analysis_account_baseline)는 콘텐츠 뷰의 계정 컬럼과 완전 일치 — 단일 원천 (07-20 분리).
  ASSERT (SELECT recent_contents_count FROM analytics.v_analysis_account_baseline WHERE account_handle = 'dummy_a') = 4,
    'account_baseline dummy_a recent_contents_count != 4';
  ASSERT (SELECT recent_reels_avg_views FROM analytics.v_analysis_account_baseline WHERE account_handle = 'dummy_a') = 6367,
    'account_baseline dummy_a recent_reels_avg_views != 6367 (콘텐츠 뷰와 일치)';
  ASSERT (SELECT recent12_avg_like_count FROM analytics.v_analysis_account_baseline WHERE account_handle = 'dummy_a') = 706,
    'account_baseline dummy_a avg_like != 706 (콘텐츠 뷰와 일치)';
  ASSERT (SELECT rank_in_recent_reels FROM analytics.v_analysis_baseline b WHERE b.short_code = 'dummy_r1')
         = (SELECT recent_reels_count FROM analytics.v_analysis_account_baseline WHERE account_handle = 'dummy_a') - 2,
    'r1 rank(1) != 계정 릴스수(3) - 2 — 콘텐츠·계정 뷰 정합 스팟체크';
END $$;

-- 최근창 밖 후보: 다작 계정에서 성숙 게시물이 최신 N개 밖으로 밀리는 상황 재현 (07-20 스코프 확장).
--   N=2로 줄이면 dummy_a 윈도우 = 최신 2개(rn·f1)만 → r1(rank 4)은 윈도우 밖.
--   기대: 콘텐츠 키 뷰(v_analysis_baseline)엔 r1이 없지만(rank 앵커 상실), 계정 뷰엔 dummy_a가 남는다
--   → 러너가 계정 평균을 앵커로 붙여 r1도 분석 가능(A-rich). 계정 집계는 윈도우(rn·f1) 기준 2건.
INSERT INTO app_setting(key, value) VALUES ('analytics.recent-window', '2');
DO $$
BEGIN
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_r1'),
    'N=2인데 r1(윈도우 밖)이 콘텐츠 키 baseline에 잔존';
  ASSERT EXISTS (SELECT 1 FROM analytics.v_analysis_account_baseline WHERE account_handle = 'dummy_a'),
    'N=2에서 dummy_a가 계정 baseline에서 사라짐 — 윈도우 밖 후보에 붙일 계정 평균 소실';
  ASSERT (SELECT recent_contents_count FROM analytics.v_analysis_account_baseline WHERE account_handle = 'dummy_a') = 2,
    '계정 baseline recent_contents_count != 2 (윈도우 N=2 미적용)';
END $$;
DELETE FROM app_setting WHERE key = 'analytics.recent-window';
