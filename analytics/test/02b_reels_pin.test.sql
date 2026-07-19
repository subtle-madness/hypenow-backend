-- 릴스 지표 고정 버그 회귀 테스트.
-- 증상: 성숙 스냅샷 중 '가장 이른 것'만 고르는 핀 로직이, 지표가 빈 타임라인 스냅샷을
--       clips 스냅샷보다 먼저 잡으면 views=NULL → hype_score=NULL이 된다 (옆에 지표 완비된
--       clips 스냅샷이 있는데도). 운영에서 릴스 1,567건이 이 경로로 점수 없음.
-- fixture: dummy_a(99990001)에 릴스 dummy_rp — 성숙 타임라인 스냅샷(06-05, views 비공개 0→NULL)이
--          성숙 clips 스냅샷(06-07, play_count 9000)보다 먼저 수집된 케이스.
-- 이 테스트 파일은 공유 seed 뒤에 같은 트랜잭션으로 붙는다(run.sh) — 아래 INSERT는 seed의 말미
-- DELETE 이후 실행되므로 살아남고, ROLLBACK으로 실DB 불변.

INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at, status, first_seen_at, origin, collect_attempts) VALUES
 (99990201,'dummy_rp','REELS','dummy_a',99990001, timestamptz '2026-06-01 09:00:00+09','PENDING', timestamptz '2026-06-01 12:00:00+09','ENUMERATION',0);

-- 타임라인 스냅샷(06-05, 성숙·최이른): video_view_count 0 → views NULL, 좋아요 400.
INSERT INTO raw_profile(influencer_id, crawl_run_id, source, username, followers, payload, captured_at) VALUES
 (99990001,99990000,'SELF_GQL','dummy_a',5500,
  '{"status":"ok","data":{"user":{"username":"dummy_a","edge_owner_to_timeline_media":{"count":1,"edges":[
     {"node":{"shortcode":"dummy_rp","product_type":"clips","video_view_count":0,
              "edge_media_preview_like":{"count":400},"edge_media_to_comment":{"count":40},
              "edge_media_to_caption":{"edges":[{"node":{"text":"cap rp tl"}}]},
              "display_url":"https://thumb/rp_tl.jpg"}}
   ]}}}}'::jsonb,
  timestamptz '2026-06-05 12:00:00+09');

-- clips 스냅샷(06-07, 성숙·나중): play_count 9000, 좋아요 410 — 지표 완비.
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at) VALUES
 (99990001,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_rp","product_type":"clips","taken_at":1780272000,"like_count":410,"comment_count":41,"play_count":9000,"video_duration":22.0,"is_paid_partnership":false,"caption":{"text":"cap rp"},"image_versions2":{"candidates":[{"url":"https://thumb/rp_v1.jpg"}]}}}]}}'::jsonb, timestamptz '2026-06-07 12:00:00+09');

DO $$
BEGIN
  -- 핀은 지표 완비된 clips 스냅샷을 골라야 한다 (빈 타임라인이 더 이르더라도).
  ASSERT (SELECT views FROM analytics.v_contents WHERE short_code = 'dummy_rp') = 9000,
    'v_contents rp views != 9000 (지표 완비 clips 스냅샷을 핀해야 함)';
  ASSERT (SELECT likes FROM analytics.v_contents WHERE short_code = 'dummy_rp') = 410,
    'v_contents rp likes != 410 (타임라인 400이 아니라 clips 소스여야 함)';
  ASSERT (SELECT hype_score FROM analytics.v_contents WHERE short_code = 'dummy_rp') IS NOT NULL,
    'v_contents rp hype_score is null (버그: 빈 타임라인 스냅샷을 핀함)';
END $$;
