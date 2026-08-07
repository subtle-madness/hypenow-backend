-- 인플루언서 상세 기대값. dummy_a 윈도우(업로드순): r1(11000)·r2(8000)·f1(NULL)·rn(100).
-- r1은 지표 핀(v_pinned_metrics, 성숙 최이른 clips 06-05=11000/520) — 랭킹과 동일 스냅샷.
-- metric='views'(views>0 3개 ≥ max(3, 4/2)), trend: older avg(11000,8000)=9500 / newer 100(f1 NULL 제외)
-- → -99% down. avg_er_pct = avg(572,330,2100,6 각각 /5500)*100 = 13.7(반올림 동일).
-- dummy_b: 표본 2(r3 + ra1(액터)) → metric='likes'(views_count 2 < 3), 둘 다 광고라 organic 표본 0 → ad_drop_pct NULL.
-- ra1은 좋아요 비공개(-1→NULL)라 ad_avg(likes)에는 r3만 반영된다.
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_account_recent WHERE owner_username LIKE 'dummy_%') = 6,
    'v_account_recent dummy rows != 6 (+ra1(액터))';

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
  ASSERT (SELECT sponsored_count FROM analytics.v_account_summaries WHERE handle = 'dummy_b') = 2,
    'summaries b sponsored_count != 2 (r3 + ra1(액터) 둘 다 유료 협찬)';
  ASSERT (SELECT ad_avg FROM analytics.v_account_summaries WHERE handle = 'dummy_b') = 1000,
    'summaries b ad_avg != 1000 (metric=likes)';
  ASSERT (SELECT ad_drop_pct FROM analytics.v_account_summaries WHERE handle = 'dummy_b') IS NULL,
    'summaries b ad_drop_pct not null (유기 표본 0)';

  -- 카테고리 믹스는 raw를 떠났다(07-21) — 구 스텁 뷰가 남아 있으면 미러 등록부와 어긋난 상태다.
  ASSERT NOT EXISTS (SELECT 1 FROM pg_views
                     WHERE schemaname = 'analytics' AND viewname = 'v_account_category_stats'),
    'analytics.v_account_category_stats still exists (V35 파생 뷰로 이관됨)';

  ASSERT (SELECT count(*) FROM analytics.v_account_content_series WHERE account_handle LIKE 'dummy_%') = 6,
    'v_account_content_series dummy rows != 6 (+ra1(액터))';
  ASSERT (SELECT sponsored FROM analytics.v_account_content_series WHERE short_code = 'dummy_r3') = true,
    'v_account_content_series r3 sponsored != true';
END $$;

-- avg_hype_score (스펙 2026-07-29-influencer-avg-hype-score + 2026-07-30-hype-score-v3-decay-after-mapping §9):
-- 최근창 콘텐츠 hype_score 단순 평균을 analytics.hype_account_score()로 매핑한 값(척도 재교정, 트랙 Z 후속).
-- 기대값을 고정하지 않고 v_contents(랭킹)와의 항등식으로 검증한다 — 같은 함수·같은 핀·같은 now()(트랜잭션 고정)라
-- 두 경로가 반드시 일치해야 하고, 시간이 지나 신선도 감쇠로 절대값이 변해도 테스트가 안 깨진다.
-- 평균은 반올림하지 않고 그대로 매핑 함수에 넘겨야 하므로(이중 반올림 제거) round는 매핑 함수 밖에서 하지 않는다.
DO $$
BEGIN
  ASSERT (SELECT avg_hype_score FROM analytics.v_account_summaries WHERE handle = 'dummy_a')
         BETWEEN 0 AND 100,
    'summaries a avg_hype_score not in 0..100';
  ASSERT (SELECT avg_hype_score FROM analytics.v_account_summaries WHERE handle = 'dummy_a')
       = (SELECT analytics.hype_account_score(avg(c.hype_score))
          FROM analytics.v_contents c
          JOIN analytics.v_account_content_series s ON s.short_code = c.short_code
          WHERE s.account_handle = 'dummy_a'),
    'summaries a avg_hype_score != hype_account_score(v_contents 창 평균) (같은 함수·핀·기준시각이어야 함)';
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
-- play_count·ig_play_count 둘 다 없음 → views NULL → 릴스 hype NULL (스냅샷 1건이라 우선순위 무관하게 핀됨)
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

-- 혼재 창: 유효 점수 + NULL 점수가 섞였을 때 NULL만 분모에서 빠지는지 (스펙 §7-1).
-- dummy_a(전부 유효)에 점수 불가 릴스 1건을 더해 혼재로 만든 뒤 항등식 재단언 —
-- 구현이 COALESCE(...,0)·sum/count(*)로 회귀하면 v_contents(avg는 NULL 자연 제외) 기준과 어긋나 잡힌다.
INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at,
                    status, first_seen_at, origin, collect_attempts)
VALUES (99990110, 'dummy_rx', 'REELS', 'dummy_a', 99990001, timestamptz '2026-06-02 10:00:00+09',
        'PENDING', timestamptz '2026-06-02 12:00:00+09', 'ENUMERATION', 0);
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at)
VALUES (99990001, 99990000, 'HIKER_V2_CLIPS',
  '{"response":{"status":"ok","items":[{"media":{"code":"dummy_rx","product_type":"clips","taken_at":1780365600,"like_count":50,"comment_count":5,"caption":{"text":"cap rx"}}}]}}'::jsonb,
  timestamptz '2026-06-08 12:00:00+09');
SELECT analytics.refresh_snapshot_cache();

DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_account_content_series
          WHERE account_handle = 'dummy_a' AND short_code = 'dummy_rx') = 1,
    'dummy_rx not in window (혼재 픽스처 자체가 안 만들어짐)';
  ASSERT (SELECT hype_score FROM analytics.v_contents WHERE short_code = 'dummy_rx') IS NULL,
    'dummy_rx hype not null (점수 불가 조건이 깨짐 — views가 생겼나)';
  ASSERT (SELECT avg_hype_score FROM analytics.v_account_summaries WHERE handle = 'dummy_a')
       = (SELECT analytics.hype_account_score(avg(c.hype_score))
          FROM analytics.v_contents c
          JOIN analytics.v_account_content_series s ON s.short_code = c.short_code
          WHERE s.account_handle = 'dummy_a'),
    'summaries a 혼재 창 항등식 실패 (NULL이 분모에 섞였을 가능성)';
END $$;

-- 통계 왜곡 가드 신규 컬럼 9개(스펙 2026-07-30-perf-summary-statistical-guards-design.md §6) 픽스처.
-- dummy_i: 조회수 관측 1건 + top1 점유율 100% (릴스 1건 + 피드 1건 — 릴스가 유일한 조회수 관측이라
-- max/sum이 같은 값이 되어 점유율이 자동 100%).
INSERT INTO influencer(id, username, status, followers, beauty, beauty_company, beauty_judged_at)
VALUES (99990007, 'dummy_i', 'QUALIFIED', 4000, true, false, timestamptz '2026-06-01 00:00:00+09');
INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at,
                    status, first_seen_at, origin, collect_attempts) VALUES
 (99990111,'dummy_i1','REELS','dummy_i',99990007, timestamptz '2026-06-01 09:00:00+09','PENDING', timestamptz '2026-06-01 12:00:00+09','ENUMERATION',0),
 (99990112,'dummy_i2','FEED' ,'dummy_i',99990007, timestamptz '2026-06-02 09:00:00+09','PENDING', timestamptz '2026-06-02 12:00:00+09','ENUMERATION',0);
INSERT INTO raw_profile(influencer_id, crawl_run_id, source, username, followers, payload, captured_at)
VALUES (99990007, 99990000, 'HIKER_MOBILE', 'dummy_i', 4000,
  '{"status":"ok","user":{"username":"dummy_i","full_name":"더미 아이","follower_count":4000}}'::jsonb,
  timestamptz '2026-06-01 12:00:00+09');
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at) VALUES
 (99990007,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_i1","product_type":"clips","taken_at":1780272000,"like_count":200,"comment_count":20,"play_count":5000,"caption":{"text":"cap i1"}}}]}}'::jsonb, timestamptz '2026-06-07 12:00:00+09'),
 (99990007,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_i2","product_type":"clips","taken_at":1780358400,"like_count":50,"comment_count":5,"caption":{"text":"cap i2"}}}]}}'::jsonb, timestamptz '2026-06-07 12:00:00+09');

-- dummy_j: 창 길이 장기(365일+) — 릴스 2건이 1년 넘게 떨어져 있다.
INSERT INTO influencer(id, username, status, followers, beauty, beauty_company, beauty_judged_at)
VALUES (99990008, 'dummy_j', 'QUALIFIED', 6000, true, false, timestamptz '2026-06-01 00:00:00+09');
INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at,
                    status, first_seen_at, origin, collect_attempts) VALUES
 (99990113,'dummy_j1','REELS','dummy_j',99990008, timestamptz '2025-01-01 09:00:00+09','PENDING', timestamptz '2025-01-01 12:00:00+09','ENUMERATION',0),
 (99990114,'dummy_j2','REELS','dummy_j',99990008, timestamptz '2026-06-01 09:00:00+09','PENDING', timestamptz '2026-06-01 12:00:00+09','ENUMERATION',0);
INSERT INTO raw_profile(influencer_id, crawl_run_id, source, username, followers, payload, captured_at)
VALUES (99990008, 99990000, 'HIKER_MOBILE', 'dummy_j', 6000,
  '{"status":"ok","user":{"username":"dummy_j","full_name":"더미 제이","follower_count":6000}}'::jsonb,
  timestamptz '2025-01-01 12:00:00+09');
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at) VALUES
 (99990008,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_j1","product_type":"clips","taken_at":1735686000,"like_count":100,"comment_count":10,"play_count":2000,"caption":{"text":"cap j1"}}}]}}'::jsonb, timestamptz '2025-01-05 12:00:00+09'),
 (99990008,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_j2","product_type":"clips","taken_at":1780358400,"like_count":200,"comment_count":20,"play_count":3000,"caption":{"text":"cap j2"}}}]}}'::jsonb, timestamptz '2026-06-05 12:00:00+09');

-- dummy_k: 피드 전용(조회수 전무) — 릴스가 없어 median_views·top_views_share_pct가 NULL이어야 한다.
INSERT INTO influencer(id, username, status, followers, beauty, beauty_company, beauty_judged_at)
VALUES (99990009, 'dummy_k', 'QUALIFIED', 7000, true, false, timestamptz '2026-06-01 00:00:00+09');
INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at,
                    status, first_seen_at, origin, collect_attempts) VALUES
 (99990115,'dummy_k1','FEED','dummy_k',99990009, timestamptz '2026-06-01 09:00:00+09','PENDING', timestamptz '2026-06-01 12:00:00+09','ENUMERATION',0),
 (99990116,'dummy_k2','FEED','dummy_k',99990009, timestamptz '2026-06-02 09:00:00+09','PENDING', timestamptz '2026-06-02 12:00:00+09','ENUMERATION',0);
INSERT INTO raw_profile(influencer_id, crawl_run_id, source, username, followers, payload, captured_at)
VALUES (99990009, 99990000, 'HIKER_MOBILE', 'dummy_k', 7000,
  '{"status":"ok","user":{"username":"dummy_k","full_name":"더미 케이","follower_count":7000}}'::jsonb,
  timestamptz '2026-06-01 12:00:00+09');
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at) VALUES
 (99990009,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_k1","product_type":"clips","taken_at":1780272000,"like_count":30,"comment_count":3,"caption":{"text":"cap k1"}}}]}}'::jsonb, timestamptz '2026-06-07 12:00:00+09'),
 (99990009,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_k2","product_type":"clips","taken_at":1780358400,"like_count":40,"comment_count":4,"caption":{"text":"cap k2"}}}]}}'::jsonb, timestamptz '2026-06-07 12:00:00+09');

SELECT analytics.refresh_snapshot_cache();

DO $$
BEGIN
  -- dummy_i: 조회수 관측 1건(views_sample_count) & top1 점유율 100%.
  ASSERT (SELECT views_sample_count FROM analytics.v_account_summaries WHERE handle = 'dummy_i') = 1,
    'summaries i views_sample_count != 1';
  ASSERT (SELECT reels_count FROM analytics.v_account_summaries WHERE handle = 'dummy_i') = 1,
    'summaries i reels_count != 1';
  ASSERT (SELECT feed_count FROM analytics.v_account_summaries WHERE handle = 'dummy_i') = 1,
    'summaries i feed_count != 1';
  ASSERT (SELECT likes_sample_count FROM analytics.v_account_summaries WHERE handle = 'dummy_i') = 2,
    'summaries i likes_sample_count != 2';
  ASSERT (SELECT comments_sample_count FROM analytics.v_account_summaries WHERE handle = 'dummy_i') = 2,
    'summaries i comments_sample_count != 2';
  ASSERT (SELECT median_views FROM analytics.v_account_summaries WHERE handle = 'dummy_i') = 5000,
    'summaries i median_views != 5000 (관측 1건 — median = 그 값)';
  ASSERT (SELECT top_views_share_pct FROM analytics.v_account_summaries WHERE handle = 'dummy_i') = 100,
    'summaries i top_views_share_pct != 100 (관측 1건이면 max=sum)';
  ASSERT (SELECT median_er_pct FROM analytics.v_account_summaries WHERE handle = 'dummy_i') = 3.4,
    'summaries i median_er_pct != 3.4 (median(220/4000, 55/4000)*100)';

  -- dummy_j: 창 길이 장기(365일+) — 날짜 산술과의 항등식으로 검증(수기 일수 계산 오류 방지).
  ASSERT (SELECT window_span_days FROM analytics.v_account_summaries WHERE handle = 'dummy_j')
       = (DATE '2026-06-01' - DATE '2025-01-01'),
    'summaries j window_span_days != 날짜차 (동일 시각대라 시분초 성분 없음)';
  ASSERT (SELECT window_span_days FROM analytics.v_account_summaries WHERE handle = 'dummy_j') > 365,
    'summaries j window_span_days <= 365 (365일+ 케이스가 안 됨)';

  -- dummy_k: 피드 전용(조회수 전무) — median/share가 NULL이어야 한다.
  ASSERT (SELECT reels_count FROM analytics.v_account_summaries WHERE handle = 'dummy_k') = 0,
    'summaries k reels_count != 0';
  ASSERT (SELECT feed_count FROM analytics.v_account_summaries WHERE handle = 'dummy_k') = 2,
    'summaries k feed_count != 2';
  ASSERT (SELECT views_sample_count FROM analytics.v_account_summaries WHERE handle = 'dummy_k') = 0,
    'summaries k views_sample_count != 0';
  ASSERT (SELECT median_views FROM analytics.v_account_summaries WHERE handle = 'dummy_k') IS NULL,
    'summaries k median_views not null (피드 전용은 조회수 관측이 없어야 함)';
  ASSERT (SELECT top_views_share_pct FROM analytics.v_account_summaries WHERE handle = 'dummy_k') IS NULL,
    'summaries k top_views_share_pct not null (관측 없으면 NULL)';
  ASSERT (SELECT likes_sample_count FROM analytics.v_account_summaries WHERE handle = 'dummy_k') = 2,
    'summaries k likes_sample_count != 2';
END $$;
