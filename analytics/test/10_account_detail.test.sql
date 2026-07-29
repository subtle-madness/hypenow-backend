-- 인플루언서 상세 기대값. dummy_a 윈도우(업로드순): r1(11000)·r2(8000)·f1(NULL)·rn(100).
-- r1은 지표 핀(v_pinned_metrics, 성숙 최이른 clips 06-05=11000/520) — 랭킹과 동일 스냅샷.
-- metric='views'(views>0 3개 ≥ max(3, 4/2)), trend: older avg(11000,8000)=9500 / newer 100(f1 NULL 제외)
-- → -99% down. avg_er_pct = avg(572,330,2100,6 각각 /5500)*100 = 13.7(반올림 동일).
-- dummy_b: 표본 1 → metric='likes'(views_count 1 < 3), r3가 광고라 organic 표본 0 → ad_drop_pct NULL.
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_account_recent WHERE owner_username LIKE 'dummy_%') = 5,
    'v_account_recent dummy rows != 5';

  ASSERT (SELECT analyzed_count FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 4,
    'summaries a analyzed_count != 4';
  ASSERT (SELECT followers FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 5500,
    'summaries a followers != 5500';
  ASSERT (SELECT views_count FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 3,
    'summaries a views_count != 3';
  ASSERT (SELECT avg_views FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 6367,
    'summaries a avg_views != 6367 (avg(11000,8000,100)=6366.67)';
  ASSERT (SELECT metric FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 'views',
    'summaries a metric != views';
  ASSERT (SELECT avg_er_pct FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 13.7,
    'summaries a avg_er_pct != 13.7';
  ASSERT (SELECT trend_direction FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = 'down',
    'summaries a trend != down';
  ASSERT (SELECT trend_change_pct FROM analytics.v_account_summaries WHERE handle = 'dummy_a') = -99,
    'summaries a trend_change_pct != -99';
  ASSERT (SELECT metric FROM analytics.v_account_summaries WHERE handle = 'dummy_b') = 'likes',
    'summaries b metric != likes (views 표본 1 < 3)';
  ASSERT (SELECT sponsored_count FROM analytics.v_account_summaries WHERE handle = 'dummy_b') = 1,
    'summaries b sponsored_count != 1';
  ASSERT (SELECT ad_avg FROM analytics.v_account_summaries WHERE handle = 'dummy_b') = 1000,
    'summaries b ad_avg != 1000 (metric=likes)';
  ASSERT (SELECT ad_drop_pct FROM analytics.v_account_summaries WHERE handle = 'dummy_b') IS NULL,
    'summaries b ad_drop_pct not null (유기 표본 0)';

  -- 카테고리 믹스는 raw를 떠났다(07-21) — 구 스텁 뷰가 남아 있으면 미러 등록부와 어긋난 상태다.
  ASSERT NOT EXISTS (SELECT 1 FROM pg_views
                     WHERE schemaname = 'analytics' AND viewname = 'v_account_category_stats'),
    'analytics.v_account_category_stats still exists (V35 파생 뷰로 이관됨)';

  ASSERT (SELECT count(*) FROM analytics.v_account_content_series WHERE account_handle LIKE 'dummy_%') = 5,
    'v_account_content_series dummy rows != 5';
  ASSERT (SELECT sponsored FROM analytics.v_account_content_series WHERE short_code = 'dummy_r3') = true,
    'v_account_content_series r3 sponsored != true';
END $$;

-- avg_hype_score (스펙 2026-07-29-influencer-avg-hype-score): 최근창 콘텐츠 hype_score 단순 평균.
-- 기대값을 고정하지 않고 v_contents(랭킹)와의 항등식으로 검증한다 — 같은 함수·같은 핀·같은 now()(트랜잭션 고정)라
-- 두 경로가 반드시 일치해야 하고, 시간이 지나 신선도 감쇠로 절대값이 변해도 테스트가 안 깨진다.
DO $$
BEGIN
  ASSERT (SELECT avg_hype_score FROM analytics.v_account_summaries WHERE handle = 'dummy_a')
         BETWEEN 0 AND 100,
    'summaries a avg_hype_score not in 0..100';
  ASSERT (SELECT avg_hype_score FROM analytics.v_account_summaries WHERE handle = 'dummy_a')
       = (SELECT round(avg(c.hype_score))::bigint
          FROM analytics.v_contents c
          JOIN analytics.v_account_content_series s ON s.short_code = c.short_code
          WHERE s.account_handle = 'dummy_a'),
    'summaries a avg_hype_score != v_contents 창 평균 (같은 함수·핀·기준시각이어야 함)';
END $$;

-- 점수 불가 창 계정: 릴스인데 조회수 없는 스냅샷만 → hype NULL → 계정 avg_hype_score NULL.
-- 다른 테스트 파일과 시드(dummy.sql)를 공유하므로 시드는 건드리지 않고 이 파일 안에서만 추가한다
-- (위 DO 블록의 기존 카운트 단언들은 이 INSERT보다 먼저 실행돼 영향 없음).
INSERT INTO influencer(id, username, status, followers, beauty, beauty_company, beauty_judged_at)
VALUES (99990006, 'dummy_h', 'QUALIFIED', 3000, true, false, timestamptz '2026-06-01 00:00:00+09');
INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at,
                    status, first_seen_at, origin, collect_attempts)
VALUES (99990109, 'dummy_h1', 'REELS', 'dummy_h', 99990006, timestamptz '2026-06-01 09:00:00+09',
        'PENDING', timestamptz '2026-06-01 12:00:00+09', 'ENUMERATION', 0);
INSERT INTO raw_profile(influencer_id, crawl_run_id, source, username, followers, payload, captured_at)
VALUES (99990006, 99990000, 'HIKER_MOBILE', 'dummy_h', 3000,
  '{"status":"ok","user":{"username":"dummy_h","full_name":"더미 에이치","follower_count":3000}}'::jsonb,
  timestamptz '2026-06-01 12:00:00+09');
-- play_count·ig_play_count 둘 다 없음 → views NULL → 릴스 hype NULL (핀 우선순위 ④ 최신으로 여전히 핀됨)
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at)
VALUES (99990006, 99990000, 'HIKER_V2_CLIPS',
  '{"response":{"status":"ok","items":[{"media":{"code":"dummy_h1","product_type":"clips","taken_at":1780272000,"like_count":100,"comment_count":10,"caption":{"text":"cap h1"}}}]}}'::jsonb,
  timestamptz '2026-06-07 12:00:00+09');
SELECT analytics.refresh_snapshot_cache();

DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_account_summaries WHERE handle = 'dummy_h') = 1,
    'summaries h row missing (점수 불가 콘텐츠도 창에는 있어야 함)';
  ASSERT (SELECT avg_hype_score FROM analytics.v_account_summaries WHERE handle = 'dummy_h') IS NULL,
    'summaries h avg_hype_score not null (창 전체 점수 불가면 NULL)';
  ASSERT (SELECT avg_likes FROM analytics.v_account_summaries WHERE handle = 'dummy_h') = 100,
    'summaries h avg_likes != 100 (다른 집계는 살아야 함)';
END $$;
