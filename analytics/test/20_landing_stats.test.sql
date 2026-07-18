-- 랜딩 통계 뷰 기대값. 시드 근거:
--   시드 계정 2개가 이미 마이크로 모수 안 — dummy_a followers=5500(최신 스냅샷), dummy_b=20000.
--   raw_profile.username·followers는 신스키마에서 crawler가 추출해 채우는 실컬럼 —
--   뷰는 컬럼을 읽으므로 픽스처도 컬럼에 직접 넣는다(payload는 원형 재현용 최소만).
--   시드 콘텐츠 4건은 모두 상세가 있고 소유자가 마이크로(dummy_a 릴스2+피드1, dummy_b 릴스1).
--   조회수는 v_base_detail = 최신 스냅샷 기준: dummy_r1=11000(06-06 최신), dummy_r2=7000, dummy_r3=40000.
--     → total_views = 11000+7000+40000 = 58000, avg_views = round(58000/3) = 19333.
--
-- 모수 경계 픽스처: 2,999(제외) / 3,000(포함, 하한 경계) / 49,999(포함, 상한 경계) / 50,000(제외)
INSERT INTO influencer(id, username) VALUES
 (9909301,'dummy_under'), (9909302,'dummy_lower'), (9909303,'dummy_upper'), (9909304,'dummy_over');
INSERT INTO raw_profile(influencer_id, crawl_run_id, source, username, followers, payload, captured_at) VALUES
 (9909301,9909990,'DATALIKERS','dummy_under',2999, '{"username":"dummy_under","full_name":"경계 아래","follower_count":2999}'::jsonb,  now()),
 (9909302,9909990,'DATALIKERS','dummy_lower',3000, '{"username":"dummy_lower","full_name":"하한","follower_count":3000}'::jsonb,      now()),
 (9909303,9909990,'DATALIKERS','dummy_upper',49999,'{"username":"dummy_upper","full_name":"상한","follower_count":49999}'::jsonb,     now()),
 (9909304,9909990,'DATALIKERS','dummy_over',50000, '{"username":"dummy_over","full_name":"경계 위","follower_count":50000}'::jsonb,   now());

-- ① 피드에 조회수가 있는 픽스처(구 크롤 잔재 재현 — 실데이터 Cr8TkbLrIZU 1건과 같은 형태):
--    콘텐츠 수에는 들어가지만 total_views에는 절대 들어가면 안 된다 (릴스 전용 규칙 실증).
-- ② 모수 밖(50,000) 계정의 릴스: 콘텐츠 수·조회수 어디에도 들어가면 안 된다 (콘텐츠도 마이크로 모수).
INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at, status, origin, first_seen_at) VALUES
 (9909305,'dummy_fv','FEED', 'dummy_a',9909001,    timestamptz '2026-06-08 09:00:00+09','COLLECTED','ENUMERATION', timestamptz '2026-06-08 00:00:00+09'),
 (9909306,'dummy_ro','REELS','dummy_over',9909304, timestamptz '2026-06-08 09:00:00+09','COLLECTED','ENUMERATION', timestamptz '2026-06-08 00:00:00+09');
INSERT INTO raw_media_page(id, influencer_id, crawl_run_id, source, payload, captured_at) VALUES
 (9909431,9909001,9909990,'HIKER_V2_CLIPS',
  '{"response":{"items":[{"media":{"code":"dummy_fv","media_type":1,"caption":{"text":"cap fv"},"like_count":10,"comment_count":1,"play_count":999,"image_versions2":{"candidates":[{"url":"https://thumb/fv.jpg"}]}}}]}}'::jsonb, now()),
 (9909432,9909304,9909990,'HIKER_V2_CLIPS',
  '{"response":{"items":[{"media":{"code":"dummy_ro","media_type":2,"caption":{"text":"cap ro"},"like_count":10,"comment_count":1,"play_count":123456,"image_versions2":{"candidates":[{"url":"https://thumb/ro.jpg"}]}}}]}}'::jsonb, now());

DO $$
DECLARE s record;
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_landing_stats) = 1, '뷰가 1행이 아님';
  SELECT * INTO s FROM analytics.v_landing_stats;

  -- 모수: 시드 dummy_a(5500)·dummy_b(20000) + 경계 픽스처 dummy_lower(3000)·dummy_upper(49999) = 4
  ASSERT s.influencers_count = 4,
    'influencers_count != 4 (마이크로 경계 위반): ' || s.influencers_count;
  ASSERT s.followers3k10k = 2, 'followers3k10k != 2 (dummy_a 5500 + dummy_lower 3000): ' || s.followers3k10k;
  ASSERT s.followers10k30k = 1, 'followers10k30k != 1 (dummy_b 20000): ' || s.followers10k30k;
  ASSERT s.followers30k50k = 1, 'followers30k50k != 1 (dummy_upper 49999): ' || s.followers30k50k;
  -- 구간 합 = 전체 마이크로 수 (was의 합계 100 보정 전제)
  ASSERT s.followers3k10k + s.followers10k30k + s.followers30k50k = s.influencers_count,
    '구간 합 != influencers_count';

  -- 콘텐츠 모수: 시드 4건 + 피드(조회수 있음) 1건 = 5. 모수 밖 dummy_over의 릴스는 제외.
  ASSERT s.contents_count = 5,
    'contents_count != 5 (시드 4 + dummy_fv, dummy_over 릴스는 제외): ' || s.contents_count;

  -- 조회수는 릴스만 (피드 dummy_fv의 999가 새면 안 됨) + 마이크로 모수만 (dummy_ro의 123456 제외)
  ASSERT s.total_views = 58000,
    'total_views != 58000 (릴스 11000+7000+40000, 피드·모수 밖 제외): ' || s.total_views;
  ASSERT s.avg_views = 19333, 'avg_views != 19333 (round(58000/3)): ' || s.avg_views;

  -- 뷰 정의와 독립적인 재계산으로 교차 검증 (릴스·마이크로 모수)
  ASSERT s.total_views = (SELECT COALESCE(sum(d.views), 0) FROM analytics.v_base_content c
                          JOIN analytics.v_base_detail d USING (content_id)
                          JOIN analytics.v_base_profile p ON p.username = c.owner_username
                          WHERE lower(c.content_type) = 'reels'
                            AND p.followers >= 3000 AND p.followers < 50000),
    'total_views가 릴스·마이크로 모수와 불일치';

  -- record Long 매핑 (sum/round(avg)의 numeric이 새면 미러가 PSQLException)
  ASSERT pg_typeof(s.total_views) = 'bigint'::regtype, 'total_views 타입 != bigint';
  ASSERT pg_typeof(s.avg_views) = 'bigint'::regtype, 'avg_views 타입 != bigint';
  ASSERT pg_typeof(s.contents_count) = 'bigint'::regtype, 'contents_count 타입 != bigint';
  ASSERT pg_typeof(s.followers3k10k) = 'bigint'::regtype, 'followers3k10k 타입 != bigint';
  ASSERT pg_typeof(s.updated_at) = 'timestamptz'::regtype, 'updated_at 타입 != timestamptz';

  -- updated_at = 뷰 실행 시각 ("매주 갱신 중" 표기 근거)
  ASSERT s.updated_at = now(), 'updated_at != now() (미러 실행 시각이어야 함)';
END $$;

-- 계정 0명이어도 1행(0으로 채움)인지: 모수를 비운 상태에서 확인 후 되돌린다.
-- 트랜잭션 안이라 실DB 불변이지만, 다른 ASSERT에 영향을 주지 않도록 SAVEPOINT로 격리한다.
SAVEPOINT before_empty;
DELETE FROM raw_profile;
DO $$
DECLARE s record;
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_landing_stats) = 1, '모수 0명일 때 뷰가 1행이 아님';
  SELECT * INTO s FROM analytics.v_landing_stats;
  ASSERT s.influencers_count = 0 AND s.contents_count = 0,
    '모수 0명일 때 카운트가 0이 아님';
  ASSERT s.total_views = 0 AND s.avg_views = 0,
    '모수 0명일 때 조회수가 0이 아님 (COALESCE 누락 — NULL이면 안 됨)';
END $$;
ROLLBACK TO SAVEPOINT before_empty;
