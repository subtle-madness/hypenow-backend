-- LLM 후보 자격 (날짜기준 제때 크롤 가드): usable 스냅샷의 캡처 캘린더일(KST)이
-- 업로드일 + pin(3) ~ +pin+slack(기본 1) 범위 ∧ 그 창이 완전히 지난 날.
-- 07-20 백필 재도입: 위 판정(=timely 컬럼)을 만족 못해도 최근 N개 윈도우(01 뷰, 기본 12) 안이면
-- 후보에 포함된다(timely=false로 노출) — "백필 MVP 제외"(07-19) 번복. 성숙 가드(창이 완전히
-- 지난 날)는 OR 양쪽 경로에 공통으로 적용 — 아직 창이 안 닫힌 콘텐츠는 판정 불능이라 윈도우 안이라도 제외.
-- 시드 스냅샷(캡처 캘린더일):
--   r1(업로드 06-01, D+3=06-04): usable 스냅 06-02·06-05·06-08 (06-04엔 없음) → timely=false, 단 창 안(rank<=12)이라 후보 포함
--   r2(업로드 06-02, D+3=06-05): usable 스냅 06-06(타임라인)만 → 06-05엔 없음 → timely=false, 창 안이라 후보 포함
--   f1(업로드 06-03, D+3=06-06): usable 스냅 06-06(타임라인) → D+3 딱 맞음 → timely=true로 포함
--   rn(업로드 어제): D+3이 미래 → 미성숙 제외(OR 양쪽 경로 공통 가드라 윈도우 안이어도 제외) / r3: 캡션 결측 제외

-- 경계 픽스처: "완전히 지난 날만" 검증 (now 기준)
--   dummy_cl(업로드 오늘-4, D+3=오늘-1, 그 날 usable 스냅): 창이 어제 닫힘 → timely=true로 포함
--   dummy_op(업로드 오늘-3, D+3=오늘, 오늘 usable 스냅): 창이 오늘이라 아직 안 닫힘 → 미성숙, OR 양쪽 경로 공통 가드로 제외
INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at, status, first_seen_at, origin, collect_attempts) VALUES
 (99990110,'dummy_cl','REELS','dummy_a',99990001, now() - interval '4 day','PENDING', now() - interval '4 day','ENUMERATION',0),
 (99990111,'dummy_op','REELS','dummy_a',99990001, now() - interval '3 day','PENDING', now() - interval '3 day','ENUMERATION',0);
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at) VALUES
 (99990001,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_cl","product_type":"clips","taken_at":1780000000,"like_count":200,"comment_count":20,"play_count":5000,"caption":{"text":"cap cl"},"image_versions2":{"candidates":[{"url":"https://thumb/cl.jpg"}]}}}]}}'::jsonb, now() - interval '1 day'),
 (99990001,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_op","product_type":"clips","taken_at":1780000000,"like_count":300,"comment_count":30,"play_count":6000,"caption":{"text":"cap op"},"image_versions2":{"candidates":[{"url":"https://thumb/op.jpg"}]}}}]}}'::jsonb, now());

SELECT analytics.refresh_snapshot_cache();  -- 본문 삽입분을 캐시에 반영
-- 기본(slack=1, window=12): D+3 '그 날' 하루가 timely 판정, 그 밖은 창(01 뷰) 안이면 백필로 포함.
DO $$
BEGIN
  ASSERT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_f1' AND timely),
    'f1(D+3=06-06 usable 스냅)이 timely 후보로 없음';
  -- 07-20 백필 재도입: 제때 크롤 실패(D+3 당일 usable 스냅 없음)라도 최근 윈도우(기본 12) 안이면
  -- timely=false 백필 후보로 포함 — 구 기대치(NOT EXISTS)를 뒤집는다.
  ASSERT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_r1' AND NOT timely),
    'r1(늦크롤·윈도우 안)이 timely=false 후보로 없음';
  ASSERT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_r2' AND NOT timely),
    'r2(늦크롤·윈도우 안)이 timely=false 후보로 없음';
  -- rn은 미성숙(창이 아직 안 닫힘) — OR 양쪽 경로에 공통인 성숙 가드라 윈도우 안이어도 제외.
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_rn'),
    'rn(미성숙)이 후보에 있음';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_r3'),
    'r3(캡션 결측)이 후보에 있음';
  -- 경계: 창이 완전히 지난 dummy_cl은 제때 크롤(timely=true)로 포함, 아직 오늘인 dummy_op은
  -- 미성숙이라 OR 양쪽 경로 공통 가드로 제외(윈도우 안이어도 제외).
  ASSERT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_cl' AND timely),
    'dummy_cl(창 어제 닫힘·제때)이 후보에서 빠짐';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_op'),
    'dummy_op(창이 오늘 — 아직 안 닫힘, 미성숙)이 후보에 있음';
  -- 기본 후보 = f1·dummy_cl(timely) + r1·r2(백필, 윈도우 안) 4건 — 07-20 개정으로 2→4건.
  ASSERT (SELECT count(*) FROM analytics.v_analysis_candidates WHERE account_handle LIKE 'dummy_%') = 4,
    'date-guard 기본(slack=1) 후보 != 4 (f1·cl·r1·r2)';
END $$;

-- 비-usable 가드: D+3 당일에 스냅이 있어도 지표 미완비면 timely=false. 07-20 개정으로 그 자체가
-- 후보 배제 사유는 아니게 됐다 — 최근 윈도우 안이면 timely=false 백필 후보로 포함된다.
-- dummy_pd = 업로드 06-01, D+3=06-04 당일 스냅 1건인데 play_count 없어 views NULL(릴스 비-usable).
INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at, status, first_seen_at, origin, collect_attempts) VALUES
 (99990109,'dummy_pd','REELS','dummy_a',99990001, timestamptz '2026-06-01 09:00:00+09','PENDING', timestamptz '2026-06-01 12:00:00+09','ENUMERATION',0);
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at) VALUES
 (99990001,99990000,'HIKER_V2_CLIPS','{"response":{"status":"ok","items":[{"media":{"code":"dummy_pd","product_type":"clips","taken_at":1780272000,"like_count":100,"comment_count":10,"caption":{"text":"cap pd"},"image_versions2":{"candidates":[{"url":"https://thumb/pd.jpg"}]}}}]}}'::jsonb, timestamptz '2026-06-04 12:00:00+09');
SELECT analytics.refresh_snapshot_cache();  -- 본문 삽입분을 캐시에 반영
DO $$
BEGIN
  -- 전제: dummy_pd 핀(06-04 스냅)은 D+3 당일 캘린더일, 그런데 views NULL(비-usable)
  ASSERT (SELECT (metric_captured_at AT TIME ZONE 'Asia/Seoul')::date FROM analytics.v_contents WHERE short_code = 'dummy_pd')
           = date '2026-06-04',
    'dummy_pd 핀 캡처일이 06-04(D+3)가 아님 — 시드 전제 실패';
  ASSERT (SELECT views FROM analytics.v_contents WHERE short_code = 'dummy_pd') IS NULL,
    'dummy_pd 핀 views가 NULL이 아님 — 비-usable 전제 실패';
  -- 본검증(07-20 개정): D+3 당일 스냅이 있어도 비-usable이면 timely=false이지만, 윈도우 안이라
  -- 백필 후보로는 포함된다 — 구 기대치(NOT EXISTS)를 뒤집는다.
  ASSERT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_pd' AND NOT timely),
    '비-usable(views NULL) dummy_pd가 timely=false 백필 후보로 없음';
END $$;

-- slack=2: D+3·D+4 이틀. r1(usable 06-05=D+4)·r2(usable 06-06=D+4)는 이제 timely=true 자체로 포함.
INSERT INTO app_setting(key, value) VALUES ('analytics.analyze-timely-slack-days', '2');
DO $$
BEGIN
  ASSERT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_r1' AND timely),
    'slack=2에서 r1(D+4 usable 스냅)이 timely 후보가 아님';
  ASSERT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_r2' AND timely),
    'slack=2에서 r2(D+4 usable 스냅)이 timely 후보가 아님';
  -- 고정 지표·최신 메타 승계 확인
  ASSERT (SELECT views FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_r1') = 11000,
    'r1 views != 11000 (고정 지표 승계)';
  ASSERT (SELECT caption FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_r1') = 'cap r1 v3',
    'r1 caption != cap r1 v3 (최신 메타)';
  -- dummy_op은 slack=2에서도 미성숙(창 미완료)이라 OR 양쪽 경로 공통 가드로 여전히 제외
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_op'),
    'dummy_op(창 미완료)이 slack=2에서 후보에 있음';
END $$;

-- 07-20 백필 재도입: timely 컬럼과 OR 자격 검증.
-- 이 시점엔 위에서 slack=2가 이미 적용돼 r1·r2는 timely=true로 바뀌어 있다 — OR 백필 경로
-- 자체를 검증하려면 어떤 slack에서도 비-usable이라 항상 timely=false인 dummy_pd를 쓴다.
DO $$
BEGIN
  -- 제때 크롤 성공분은 timely=true
  ASSERT (SELECT timely FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_f1'),
    'f1(제때 크롤)의 timely가 true가 아님';
  -- 늦크롤(비-usable)이지만 최근 윈도우 안 → 포함 + timely=false
  ASSERT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_pd' AND NOT timely),
    'pd(늦크롤·윈도우 안)가 timely=false 후보로 포함되지 않음';
END $$;

-- 윈도우 밖 늦크롤은 여전히 제외: recent-window를 좁혀 pd를 창 밖으로 밀어낸다.
-- (r1·r2는 이 시점에 이미 timely=true라 윈도우 축소로는 안 빠진다 — pd로 검증)
INSERT INTO app_setting(key, value) VALUES ('analytics.recent-window', '1')
  ON CONFLICT (key) DO UPDATE SET value = excluded.value;
DO $$
BEGIN
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_analysis_candidates WHERE short_code = 'dummy_pd'),
    'recent-window=1인데 늦크롤 pd가 여전히 후보에 있음 (창 밖 제외 실패)';
END $$;
-- run.sh가 파일 전체를 BEGIN/ROLLBACK으로 감싸므로(격리) app_setting 원복은 불필요.
