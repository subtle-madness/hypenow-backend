-- 이메일 파생 컬럼 (스펙 2026-07-30-influencer-email-from-bio): v_account_summaries.email.
-- 정규식 [A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}, POSIX substring은 leftmost match만 반환하므로
-- "첫 번째 매치만"이 자연히 성립. 결과는 lower()로 소문자 정규화. 계정마다 REELS 1건 최소 픽스처
-- (v_account_recent가 뷰티 인플루언서 ∧ 스냅샷 보유를 요구 — 10_account_detail.test.sql dummy_h 패턴 재사용).
INSERT INTO influencer(id, username, status, followers, beauty, beauty_company, beauty_judged_at) VALUES
 (99990020, 'dummy_em1', 'QUALIFIED', 3000, true, false, timestamptz '2026-06-01 00:00:00+09'),
 (99990021, 'dummy_em2', 'QUALIFIED', 3000, true, false, timestamptz '2026-06-01 00:00:00+09'),
 (99990022, 'dummy_em3', 'QUALIFIED', 3000, true, false, timestamptz '2026-06-01 00:00:00+09'),
 (99990023, 'dummy_em4', 'QUALIFIED', 3000, true, false, timestamptz '2026-06-01 00:00:00+09'),
 (99990024, 'dummy_em5', 'QUALIFIED', 3000, true, false, timestamptz '2026-06-01 00:00:00+09'),
 (99990025, 'dummy_em6', 'QUALIFIED', 3000, true, false, timestamptz '2026-06-01 00:00:00+09'),
 (99990026, 'dummy_em7', 'QUALIFIED', 3000, true, false, timestamptz '2026-06-01 00:00:00+09');

INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at,
                    status, first_seen_at, origin, collect_attempts) VALUES
 (99990220, 'dummy_em1_r', 'REELS', 'dummy_em1', 99990020, timestamptz '2026-06-01 09:00:00+09', 'PENDING', timestamptz '2026-06-01 12:00:00+09', 'ENUMERATION', 0),
 (99990221, 'dummy_em2_r', 'REELS', 'dummy_em2', 99990021, timestamptz '2026-06-01 09:00:00+09', 'PENDING', timestamptz '2026-06-01 12:00:00+09', 'ENUMERATION', 0),
 (99990222, 'dummy_em3_r', 'REELS', 'dummy_em3', 99990022, timestamptz '2026-06-01 09:00:00+09', 'PENDING', timestamptz '2026-06-01 12:00:00+09', 'ENUMERATION', 0),
 (99990223, 'dummy_em4_r', 'REELS', 'dummy_em4', 99990023, timestamptz '2026-06-01 09:00:00+09', 'PENDING', timestamptz '2026-06-01 12:00:00+09', 'ENUMERATION', 0),
 (99990224, 'dummy_em5_r', 'REELS', 'dummy_em5', 99990024, timestamptz '2026-06-01 09:00:00+09', 'PENDING', timestamptz '2026-06-01 12:00:00+09', 'ENUMERATION', 0),
 (99990225, 'dummy_em6_r', 'REELS', 'dummy_em6', 99990025, timestamptz '2026-06-01 09:00:00+09', 'PENDING', timestamptz '2026-06-01 12:00:00+09', 'ENUMERATION', 0),
 (99990226, 'dummy_em7_r', 'REELS', 'dummy_em7', 99990026, timestamptz '2026-06-01 09:00:00+09', 'PENDING', timestamptz '2026-06-01 12:00:00+09', 'ENUMERATION', 0);

-- em1: 표준 이메일. em2: 한글이 바로 앞에 붙음(로컬파트 경계 확인). em3: 인스타 멘션만(오탐 방지).
-- em4: 이메일 2개(첫 번째만). em5: 대문자(소문자 정규화). em6: biography 키 자체 없음(NULL). em7: 뒤에 마침표.
INSERT INTO raw_profile(influencer_id, crawl_run_id, source, username, followers, payload, captured_at) VALUES
 (99990020, 99990000, 'HIKER_MOBILE', 'dummy_em1', 3000,
  '{"status":"ok","user":{"username":"dummy_em1","follower_count":3000,"biography":"뷰티 크리에이터입니다. 문의: abc@gmail.com"}}'::jsonb,
  timestamptz '2026-06-01 12:00:00+09'),
 (99990021, 99990000, 'HIKER_MOBILE', 'dummy_em2', 3000,
  '{"status":"ok","user":{"username":"dummy_em2","follower_count":3000,"biography":"문의는abc@gmail.com으로 부탁드려요"}}'::jsonb,
  timestamptz '2026-06-01 12:00:00+09'),
 (99990022, 99990000, 'HIKER_MOBILE', 'dummy_em3', 3000,
  '{"status":"ok","user":{"username":"dummy_em3","follower_count":3000,"biography":"데일리룩은 @handle 에서 확인하세요"}}'::jsonb,
  timestamptz '2026-06-01 12:00:00+09'),
 (99990023, 99990000, 'HIKER_MOBILE', 'dummy_em4', 3000,
  '{"status":"ok","user":{"username":"dummy_em4","follower_count":3000,"biography":"abc@gmail.com 또는 xyz@naver.com 으로 연락주세요"}}'::jsonb,
  timestamptz '2026-06-01 12:00:00+09'),
 (99990024, 99990000, 'HIKER_MOBILE', 'dummy_em5', 3000,
  '{"status":"ok","user":{"username":"dummy_em5","follower_count":3000,"biography":"Contact: ABC@GMAIL.COM"}}'::jsonb,
  timestamptz '2026-06-01 12:00:00+09'),
 (99990025, 99990000, 'HIKER_MOBILE', 'dummy_em6', 3000,
  '{"status":"ok","user":{"username":"dummy_em6","follower_count":3000}}'::jsonb,
  timestamptz '2026-06-01 12:00:00+09'),
 (99990026, 99990000, 'HIKER_MOBILE', 'dummy_em7', 3000,
  '{"status":"ok","user":{"username":"dummy_em7","follower_count":3000,"biography":"문의: abc@gmail.com."}}'::jsonb,
  timestamptz '2026-06-01 12:00:00+09');

INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at) VALUES
 (99990020, 99990000, 'HIKER_V2_CLIPS', '{"response":{"status":"ok","items":[{"media":{"code":"dummy_em1_r","product_type":"clips","taken_at":1780272000,"like_count":100,"comment_count":10,"play_count":1000,"caption":{"text":"cap em1"}}}]}}'::jsonb, timestamptz '2026-06-07 12:00:00+09'),
 (99990021, 99990000, 'HIKER_V2_CLIPS', '{"response":{"status":"ok","items":[{"media":{"code":"dummy_em2_r","product_type":"clips","taken_at":1780272000,"like_count":100,"comment_count":10,"play_count":1000,"caption":{"text":"cap em2"}}}]}}'::jsonb, timestamptz '2026-06-07 12:00:00+09'),
 (99990022, 99990000, 'HIKER_V2_CLIPS', '{"response":{"status":"ok","items":[{"media":{"code":"dummy_em3_r","product_type":"clips","taken_at":1780272000,"like_count":100,"comment_count":10,"play_count":1000,"caption":{"text":"cap em3"}}}]}}'::jsonb, timestamptz '2026-06-07 12:00:00+09'),
 (99990023, 99990000, 'HIKER_V2_CLIPS', '{"response":{"status":"ok","items":[{"media":{"code":"dummy_em4_r","product_type":"clips","taken_at":1780272000,"like_count":100,"comment_count":10,"play_count":1000,"caption":{"text":"cap em4"}}}]}}'::jsonb, timestamptz '2026-06-07 12:00:00+09'),
 (99990024, 99990000, 'HIKER_V2_CLIPS', '{"response":{"status":"ok","items":[{"media":{"code":"dummy_em5_r","product_type":"clips","taken_at":1780272000,"like_count":100,"comment_count":10,"play_count":1000,"caption":{"text":"cap em5"}}}]}}'::jsonb, timestamptz '2026-06-07 12:00:00+09'),
 (99990025, 99990000, 'HIKER_V2_CLIPS', '{"response":{"status":"ok","items":[{"media":{"code":"dummy_em6_r","product_type":"clips","taken_at":1780272000,"like_count":100,"comment_count":10,"play_count":1000,"caption":{"text":"cap em6"}}}]}}'::jsonb, timestamptz '2026-06-07 12:00:00+09'),
 (99990026, 99990000, 'HIKER_V2_CLIPS', '{"response":{"status":"ok","items":[{"media":{"code":"dummy_em7_r","product_type":"clips","taken_at":1780272000,"like_count":100,"comment_count":10,"play_count":1000,"caption":{"text":"cap em7"}}}]}}'::jsonb, timestamptz '2026-06-07 12:00:00+09');

SELECT analytics.refresh_snapshot_cache();

DO $$
BEGIN
  ASSERT (SELECT email FROM analytics.v_account_summaries WHERE handle = 'dummy_em1') = 'abc@gmail.com',
    'em1 표준 이메일 파싱 실패';
  ASSERT (SELECT email FROM analytics.v_account_summaries WHERE handle = 'dummy_em2') = 'abc@gmail.com',
    'em2 한글 바로 뒤 로컬파트 경계 실패(문의는abc@gmail.com으로)';
  ASSERT (SELECT email FROM analytics.v_account_summaries WHERE handle = 'dummy_em3') IS NULL,
    'em3 인스타 멘션(@handle)이 이메일로 오탐됨';
  ASSERT (SELECT email FROM analytics.v_account_summaries WHERE handle = 'dummy_em4') = 'abc@gmail.com',
    'em4 이메일 2개 중 첫 번째만 취하기 실패';
  ASSERT (SELECT email FROM analytics.v_account_summaries WHERE handle = 'dummy_em5') = 'abc@gmail.com',
    'em5 소문자 정규화 실패';
  ASSERT (SELECT email FROM analytics.v_account_summaries WHERE handle = 'dummy_em6') IS NULL,
    'em6 biography NULL인데 email이 NULL이 아님';
  ASSERT (SELECT email FROM analytics.v_account_summaries WHERE handle = 'dummy_em7') = 'abc@gmail.com',
    'em7 뒤 마침표가 이메일에 포함됨(abc@gmail.com. 처리 실패)';
END $$;
