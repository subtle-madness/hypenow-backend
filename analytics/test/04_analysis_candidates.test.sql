-- LLM 후보 자격: 뷰티 모수 ∩ ENUMERATION ∩ 캡션 존재 ∩ 숙성(3일).
-- 기대: r1·r2·f1 포함 / rn(1일 전 업로드 — 미숙성)·r3(캡션 결측) 제외.
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_analysis_candidates WHERE account_handle LIKE 'dummy_%') = 3,
    'candidates dummy rows != 3 (r1·r2·f1)';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_rn'),
    'candidates에 미숙성(rn) 존재';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_r3'),
    'candidates에 캡션 결측(r3) 존재';
  ASSERT (SELECT caption FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_r1') = 'cap r1 v3',
    'candidates r1 caption != cap r1 v3 (최신 메타)';
  ASSERT (SELECT followers FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_r1') = 5500,
    'candidates r1 followers != 5500';
  ASSERT (SELECT views FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_r1') = 11000,
    'candidates r1 views != 11000 (고정 지표 승계)';
END $$;

-- 결정론 가드(EXISTS): 핀이 창 안이라도 '창 안 usable 스냅샷'이 없으면 후보가 아니다.
-- dummy_pd = 창 [+3,+5) 안(+4일3h)에 스냅샷이 딱 1건인데 play_count 없어 views NULL(릴스 비-usable).
-- 구 핀 기반 가드는 이 스냅샷이 핀되면 "창 안"이라는 이유로 포함했다(핀 로직 변경 PR #58에 따라
-- 자격이 흔들리던 원인). 새 EXISTS 가드는 usable 스냅샷 존재만 보므로 핀·잡시점과 무관하게 제외.
INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at, status, first_seen_at, origin, collect_attempts) VALUES
 (99990109,'dummy_pd','REELS','dummy_a',99990001, timestamptz '2026-06-01 09:00:00+09','PENDING', timestamptz '2026-06-01 12:00:00+09','ENUMERATION',0);
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at) VALUES
 (99990001,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_pd","product_type":"clips","taken_at":1780272000,"like_count":100,"comment_count":10,"caption":{"text":"cap pd"},"image_versions2":{"candidates":[{"url":"https://thumb/pd_v1.jpg"}]}}}]}}'::jsonb, timestamptz '2026-06-05 12:00:00+09');
DO $$
BEGIN
  -- 전제: dummy_pd 고정 핀은 창 [+3,+5) 안 (구 가드였다면 포함됐을 조건)
  ASSERT (SELECT metric_captured_at FROM analytics.v_contents WHERE short_code = 'dummy_pd')
           >= timestamptz '2026-06-01 09:00:00+09' + interval '3 day'
     AND (SELECT metric_captured_at FROM analytics.v_contents WHERE short_code = 'dummy_pd')
           <  timestamptz '2026-06-01 09:00:00+09' + interval '5 day',
    'dummy_pd 핀이 창 밖 — 시드 전제 실패';
  -- 전제: 그 핀은 비-usable (views NULL)
  ASSERT (SELECT views FROM analytics.v_contents WHERE short_code = 'dummy_pd') IS NULL,
    'dummy_pd 핀 views가 NULL이 아님 — 비-usable 전제 실패';
  -- 본검증: 창 안 usable 스냅샷이 없으므로 후보에서 제외
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_pd'),
    'EXISTS 가드 위반: 창 안 usable 스냅샷 없는 dummy_pd가 후보에 포함';
  -- 누수 없음: 정상 후보는 여전히 3건(r1·r2·f1)
  ASSERT (SELECT count(*) FROM analytics.v_analysis_candidates WHERE account_handle LIKE 'dummy_%') = 3,
    'dummy_pd 추가 후 후보 수 != 3 (누수 의심)';
END $$;

-- 제때 크롤 가드: 창 안 usable 스냅샷 EXISTS 로 판정 —
-- 창 = [posted_at + pin(3), posted_at + pin + slack(2)). 시드는 날짜가 전부 고정이라 상대 판정이
-- 시간이 지나도 안정적이다. 여유를 1일로 좁히면 r1·r2(usable 스냅샷 +4일3h)는 창 [+3,+4) 밖 →
-- 제외, f1(usable +3일3h)만 창 안 잔존.
INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-timely-slack-days', '1');
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_analysis_candidates WHERE account_handle LIKE 'dummy_%') = 1,
    '여유 1일에서 늦크롤분(r1·r2)이 후보에 남아 있음';
  ASSERT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_f1'),
    '제때 크롤분(f1)이 후보에서 빠짐';
END $$;

-- 미성숙 지표 가드: 숙성 일수를 0으로 낮춰도 rn(성숙 스냅샷 없음 — 최신 폴백 핀, 지표 +1일 시점)은
-- 후보가 아니다 — posted_at 나이 가드만으로는 못 막던 미성숙 폴백 지표의 영구 고정 누수 차단.
INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-maturity-days', '0');
DO $$
BEGIN
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_rn'),
    '미성숙 폴백 지표(rn)가 후보에 존재';
END $$;
