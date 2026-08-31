-- 랜딩 통계 기대값. 모수 = 뷰티 인플루언서 ∩ 자격 구간(500~5만 — qualify.min-followers와 정합):
-- a(5500)·b(20000)·n(1200, 나노). co(8000)는 구간 안이지만 회사, x(9000)는 비뷰티,
-- u(499)는 하한(500) 미만 — 제외 검증.
-- 나노 계정 시드는 이 파일에서만 추가한다 — 공용 dummy.sql에 넣으면 00·02 계정 수 단언이 깨진다.
INSERT INTO influencer(id, username, status, followers, beauty, beauty_company, beauty_judged_at) VALUES
 (99990006,'dummy_n','QUALIFIED', 1200, true, false, timestamptz '2026-06-01 00:00:00+09'),
 (99990007,'dummy_u','QUALIFIED',  499, true, false, timestamptz '2026-06-01 00:00:00+09');
INSERT INTO raw_profile(influencer_id, crawl_run_id, source, username, followers, payload, captured_at) VALUES
 (99990006,99990000,'HIKER_MOBILE','dummy_n',1200,
  '{"status":"ok","user":{"username":"dummy_n","full_name":"더미 나노","follower_count":1200}}'::jsonb,
  timestamptz '2026-06-10 12:00:00+09'),
 (99990007,99990000,'HIKER_MOBILE','dummy_u',499,
  '{"status":"ok","user":{"username":"dummy_u","full_name":"더미 언더","follower_count":499}}'::jsonb,
  timestamptz '2026-06-10 12:00:00+09');
-- 나노의 콘텐츠도 모수에 들어가는지 — r6(릴스, ENUMERATION, 조회수 900).
INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at, status, first_seen_at, origin, collect_attempts) VALUES
 (99990109,'dummy_r6','REELS','dummy_n',99990006, timestamptz '2026-06-01 10:00:00+09','PENDING', timestamptz '2026-06-01 12:00:00+09','ENUMERATION',0);
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at) VALUES
 (99990006,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_r6","product_type":"clips","taken_at":1780308000,"like_count":30,"comment_count":3,"play_count":900,"caption":{"text":"cap r6"}}}]}}'::jsonb,
  timestamptz '2026-06-05 12:00:00+09');
-- 위 시드가 스냅샷 캐시에 반영되도록 재갱신 (run.sh의 1차 갱신은 이 파일 이전에 돈다).
SELECT analytics.refresh_snapshot_cache();

-- 콘텐츠 = a·b·n의 ENUMERATION(스냅샷 보유): r1·r2·f1·rn·r3·r6 + ra1(액터) = 7. d1(DISCOVERY) 제외.
-- 조회수(릴스만, 최신 스냅샷): 12000+8000+100+40000+900 + 7000(ra1(액터)) = 68000, avg = 68000/6 = 11333.
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_landing_stats) = 1, 'landing_stats != 1행';
  -- 08-31 서빙 개방 이후에도 랜딩 모수는 뷰티 유지(기본 화면) — dummy_fb(F&B 단독)가 끼면 4가 된다
  ASSERT (SELECT influencers_count FROM analytics.v_landing_stats) = 3, 'influencers_count != 3 (F&B 제외)';
  ASSERT (SELECT followers500to3k FROM analytics.v_landing_stats) = 1, 'followers500to3k != 1';
  ASSERT (SELECT followers3k10k FROM analytics.v_landing_stats) = 1, 'followers3k10k != 1';
  ASSERT (SELECT followers10k30k FROM analytics.v_landing_stats) = 1, 'followers10k30k != 1';
  ASSERT (SELECT followers30k50k FROM analytics.v_landing_stats) = 0, 'followers30k50k != 0';
  ASSERT (SELECT contents_count FROM analytics.v_landing_stats) = 7, 'contents_count != 7 (+ra1(액터))';
  ASSERT (SELECT total_views FROM analytics.v_landing_stats) = 68000, 'total_views != 68000 (+ra1(액터) 7000)';
  ASSERT (SELECT avg_views FROM analytics.v_landing_stats) = 11333, 'avg_views != 11333 (68000/6)';
END $$;
