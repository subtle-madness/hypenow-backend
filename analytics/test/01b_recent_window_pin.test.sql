-- 최근창 경로 지표 핀 버그 회귀 테스트 (이슈: recentReels·baseline 조회수 NULL).
-- 증상: v_recent_content가 지표를 v_base_detail(최신 스냅샷 승)에서 가져와,
--       빈 타임라인 스냅샷(view 비공개 0→NULL)이 지표 완비 clips 스냅샷보다 '나중'에 수집되면
--       옆에 완비 스냅샷이 있는데도 views=NULL이 된다. #58은 v_contents만 usable-pin으로 고쳤고
--       이 경로(recentReels·v_analysis_baseline·account_summaries의 밑판)는 미적용이었다.
-- fixture: dummy_pin(99990006) 릴스 dummy_rp2 — 성숙 clips(06-05, play_count 9000)가 먼저,
--          성숙 타임라인(06-09, video_view_count 0→NULL)이 나중에 수집된 케이스.
-- 02b와의 차이: 02b는 빈 타임라인이 '더 이른' 케이스라 최신-승 v_base_detail이 우연히 통과했다.
--          여기선 빈 타임라인이 '더 나중'이라 v_base_detail이 NULL을 핀한다(운영 실데이터 형태).

INSERT INTO influencer(id, username, status, followers, beauty, beauty_company, beauty_judged_at) VALUES
 (99990006,'dummy_pin','QUALIFIED', 6000, true, false, timestamptz '2026-06-01 00:00:00+09');

INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at, status, first_seen_at, origin, collect_attempts) VALUES
 (99990202,'dummy_rp2','REELS','dummy_pin',99990006, timestamptz '2026-06-01 09:00:00+09','PENDING', timestamptz '2026-06-01 12:00:00+09','ENUMERATION',0);

-- clips 스냅샷(06-05, 성숙·최이른·지표완비): play_count 9000, 좋아요 410.
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at) VALUES
 (99990006,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_rp2","product_type":"clips","taken_at":1780272000,"like_count":410,"comment_count":41,"play_count":9000,"video_duration":22.0,"is_paid_partnership":false,"caption":{"text":"cap rp2"},"image_versions2":{"candidates":[{"url":"https://thumb/rp2_v1.jpg"}]}}}]}}'::jsonb, timestamptz '2026-06-05 12:00:00+09');

-- 프로필+타임라인 스냅샷(06-09, 성숙·최신·view 비공개): video_view_count 0 → views NULL, 좋아요 400.
-- 프로필 실컬럼 followers도 제공 — v_account_recent(INNER JOIN 프로필)에 들어가기 위함.
INSERT INTO raw_profile(influencer_id, crawl_run_id, source, username, followers, payload, captured_at) VALUES
 (99990006,99990000,'SELF_GQL','dummy_pin',6000,
  '{"status":"ok","data":{"user":{"username":"dummy_pin","full_name":"더미 핀",
     "profile_pic_url":"https://pic/pin.jpg","biography":"bio pin","external_url":"https://link.example/pin",
     "edge_followed_by":{"count":6000},"edge_follow":{"count":90},
     "edge_owner_to_timeline_media":{"count":1,"edges":[
       {"node":{"shortcode":"dummy_rp2","product_type":"clips","taken_at_timestamp":1780272000,
                "video_view_count":0,
                "edge_media_preview_like":{"count":400},"edge_media_to_comment":{"count":40},
                "edge_media_to_caption":{"edges":[{"node":{"text":"cap rp2 tl"}}]},
                "display_url":"https://thumb/rp2_tl.jpg"}}
     ]}}}}'::jsonb,
  timestamptz '2026-06-09 12:00:00+09');

SELECT analytics.refresh_snapshot_cache();  -- 본문 삽입분을 캐시에 반영
DO $$
BEGIN
  -- 대조군: v_contents(#58 fixed)는 이미 usable clips를 핀해 9000이 나온다.
  ASSERT (SELECT views FROM analytics.v_contents WHERE short_code = 'dummy_rp2') = 9000,
    'v_contents rp2 views != 9000 (대조군 — 이건 이미 통과해야 정상)';

  -- 본 버그: 최근창 경로도 usable clips 지표를 써야 한다 (빈 타임라인이 더 나중이어도).
  ASSERT (SELECT views FROM analytics.v_recent_content WHERE short_code = 'dummy_rp2') = 9000,
    'v_recent_content rp2 views != 9000 (버그: v_base_detail 최신 빈 타임라인을 핀함)';

  -- 서빙 시계열(recentReels·strip 재료)도 완비 지표여야 한다.
  ASSERT (SELECT views FROM analytics.v_account_content_series WHERE short_code = 'dummy_rp2') = 9000,
    'v_account_content_series rp2 views != 9000 (recentReels 소스)';

  -- baseline(동결 전 계산 밑판): 이 릴스가 usable로 잡혀 count 1·avg 9000이어야 한다.
  ASSERT (SELECT recent_reels_count FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_rp2') = 1,
    'v_analysis_baseline rp2 recent_reels_count != 1 (usable 릴스 누락)';
  ASSERT (SELECT recent_reels_avg_views FROM analytics.v_analysis_baseline WHERE short_code = 'dummy_rp2') = 9000,
    'v_analysis_baseline rp2 recent_reels_avg_views != 9000';
END $$;
