-- 계정 하입 스코어 매핑 함수(analytics.hype_account_score) 회귀 테스트 — 트랙 Z 후속(계정 점수 척도
-- 재교정, 스펙 2026-07-30-hype-score-v3-decay-after-mapping-design.md §9, 구현 10_account_detail.sql).
-- 순수 함수 검증 — 쓰기는 app_setting 임시 오버라이드뿐(run.sh가 BEGIN/ROLLBACK으로 격리).
-- 핵심 계약: 척도만 재교정하고 raw 평균의 **순위는 절대 깨지 않는다** — 아래 1)이 그 보장이다.
DO $$
DECLARE
  s1 bigint; s2 bigint; s3 bigint; s4 bigint; top bigint;
  base bigint; raised bigint;
BEGIN
  -- 1) 단조성: raw가 크면 매핑 점수도 크거나 같다 (순위 불변 — 이번 변경의 핵심 계약).
  --    기본 앵커(p05=1.1667·p50=12.0833·p90=30.5455·p99=45.6667, 2026-08-17 재적합) 5개 구간을
  --    고루 지나는 표본.
  s1  := analytics.hype_account_score(0.5);   -- < a05
  s2  := analytics.hype_account_score(5);     -- a05~a50
  s3  := analytics.hype_account_score(20);    -- a50~a90
  s4  := analytics.hype_account_score(40);    -- a90~a99
  top := analytics.hype_account_score(60);    -- > a99 (초과구간)
  IF NOT (s1 <= s2 AND s2 <= s3 AND s3 <= s4 AND s4 <= top) THEN
    RAISE EXCEPTION '단조성 위반: %, %, %, %, %', s1, s2, s3, s4, top;
  END IF;

  -- 2) 상한 도달: 관측 최대 raw 수준(test 스택 실측 최상위 계정 beauty_linyas2 ≈ 58.9,
  --    스펙 §9 재교정 전 계정 점수 최대값 59)이 90 이상으로 매핑된다 — 척도 재교정의 목적 자체.
  IF analytics.hype_account_score(59) < 90 THEN
    RAISE EXCEPTION '상한 도달 실패: hype_account_score(59) = % (기대 >= 90)',
      analytics.hype_account_score(59);
  END IF;

  -- 3) 0 보존: 창에 콘텐츠는 있으나 raw 평균이 0인 계정은 여전히 0점.
  IF analytics.hype_account_score(0) <> 0 THEN
    RAISE EXCEPTION 'raw=0 보존 실패: %', analytics.hype_account_score(0);
  END IF;

  -- 4) NULL 보존: 창 전체 점수 불가(raw NULL, 예: 릴스뿐인데 조회수 전무) 계정은 여전히 NULL
  --    (기존 동작 — 스펙 2026-07-29-influencer-avg-hype-score).
  IF analytics.hype_account_score(NULL) IS NOT NULL THEN
    RAISE EXCEPTION 'raw=NULL 보존 실패: %', analytics.hype_account_score(NULL);
  END IF;

  -- 5) 앵커 오버라이드 반응: app_setting으로 p50을 올리면 같은 raw가 더 낮은 점수
  --    (02_serving.test.sql의 콘텐츠 앵커 단언과 동일 관용구 — 계정 앵커도 재배포 없이 튜닝 가능함을 확인).
  base := analytics.hype_account_score(20);
  INSERT INTO app_setting(key,value) VALUES
    ('analytics.hype-anchor-acct-p50','30'),
    ('analytics.hype-anchor-acct-p90','50'),
    ('analytics.hype-anchor-acct-p99','70');
  raised := analytics.hype_account_score(20);
  IF raised >= base THEN
    RAISE EXCEPTION '앵커 오버라이드 무반응: base=%, p50↑ 후=% (더 낮아야 함)', base, raised;
  END IF;
  DELETE FROM app_setting WHERE key LIKE 'analytics.hype-anchor-acct-%';

  RAISE NOTICE '10_account_score_rescale: 모든 단언 통과';
END $$;

-- 정렬 키 분리 회귀(스펙 §9 하위절 — 발굴 목록 상위권 동점이 handle 알파벳순에 지배되던 결함):
-- v_account_summaries.avg_hype_raw는 avg_hype_score를 만드는 반올림 **전** 평균 그 자체여야 하고,
-- 표시(avg_hype_score)가 동점이어도 정렬(avg_hype_raw)은 순위를 정확히 보존해야 한다.
DO $$
BEGIN
  -- a) avg_hype_raw가 NULL이 아닌 기존 dummy_a/dummy_b에서 avg_hype_score = hype_account_score(avg_hype_raw)
  --    (두 컬럼이 base CTE의 같은 avg(...)에서 파생됐음을 고정 — 매핑 함수를 두 번 다른 식으로 부르면 표류할 수 있다).
  ASSERT (SELECT avg_hype_raw FROM analytics.v_account_summaries WHERE handle = 'dummy_a') IS NOT NULL,
    'dummy_a avg_hype_raw가 NULL (전제 붕괴 — 픽스처가 바뀌었나)';
  ASSERT (SELECT avg_hype_score FROM analytics.v_account_summaries WHERE handle = 'dummy_a')
       = (SELECT analytics.hype_account_score(avg_hype_raw)
          FROM analytics.v_account_summaries WHERE handle = 'dummy_a'),
    'dummy_a avg_hype_score != hype_account_score(avg_hype_raw)';
  ASSERT (SELECT avg_hype_score FROM analytics.v_account_summaries WHERE handle = 'dummy_b')
       = (SELECT analytics.hype_account_score(avg_hype_raw)
          FROM analytics.v_account_summaries WHERE handle = 'dummy_b'),
    'dummy_b avg_hype_score != hype_account_score(avg_hype_raw)';
END $$;

-- b) avg_hype_score가 NULL인 계정(창 전체 점수 불가 — 릴스뿐인데 조회수 전무)은 avg_hype_raw도 NULL.
-- dummy_h와 같은 최소 픽스처(10_account_detail.test.sql 관용구 재사용) — 이 파일 안에서만 추가.
INSERT INTO influencer(id, username, status, followers, beauty, beauty_company, beauty_judged_at)
VALUES (99990040, 'dummy_rawnull', 'QUALIFIED', 3000, true, false, timestamptz '2026-06-01 00:00:00+09');
INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at,
                    status, first_seen_at, origin, collect_attempts)
VALUES (99990140, 'dummy_rawnull1', 'REELS', 'dummy_rawnull', 99990040, timestamptz '2026-06-01 09:00:00+09',
        'PENDING', timestamptz '2026-06-01 12:00:00+09', 'ENUMERATION', 0);
INSERT INTO raw_profile(influencer_id, crawl_run_id, source, username, followers, payload, captured_at)
VALUES (99990040, 99990000, 'HIKER_MOBILE', 'dummy_rawnull', 3000,
  '{"status":"ok","user":{"username":"dummy_rawnull","follower_count":3000}}'::jsonb,
  timestamptz '2026-06-01 12:00:00+09');
-- play_count·ig_play_count 둘 다 없음 → views NULL → 릴스 hype NULL (창 전체 점수 불가)
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at)
VALUES (99990040, 99990000, 'HIKER_V2_CLIPS',
  '{"response":{"status":"ok","items":[{"media":{"code":"dummy_rawnull1","product_type":"clips","taken_at":1780272000,"like_count":100,"comment_count":10,"caption":{"text":"cap rawnull1"}}}]}}'::jsonb,
  timestamptz '2026-06-07 12:00:00+09');
SELECT analytics.refresh_snapshot_cache();

DO $$
BEGIN
  ASSERT (SELECT avg_hype_score FROM analytics.v_account_summaries WHERE handle = 'dummy_rawnull') IS NULL,
    'dummy_rawnull avg_hype_score not null (전제 붕괴)';
  ASSERT (SELECT avg_hype_raw FROM analytics.v_account_summaries WHERE handle = 'dummy_rawnull') IS NULL,
    'dummy_rawnull avg_hype_raw not null (avg_hype_score NULL이면 avg_hype_raw도 NULL이어야 함)';
  -- 고정 분모 도입(2026-07-31) 후에도 유지되어야 하는 계약: 창 전체 점수 불가면 sum()도 NULL이라
  -- NULL/고정분모=NULL이고, 매핑 함수도 raw NULL을 NULL로 통과시키므로 avg_hype_score_precise도 NULL.
  ASSERT (SELECT avg_hype_score_precise FROM analytics.v_account_summaries WHERE handle = 'dummy_rawnull') IS NULL,
    'dummy_rawnull avg_hype_score_precise not null (창 전체 점수 불가면 NULL이어야 함 — 고정 분모 도입 회귀)';
END $$;

-- 핵심 회귀: 표시 점수가 동점인 두 계정을 만들어 정렬이 raw 순서를 따르는지 확인한다.
-- dummy_alpha(피드 2건, likes 16400·17800 → 콘텐츠 점수 38·40 → raw 평균 39.0)
-- dummy_zeta (피드 2건, likes 17100·17800 → 콘텐츠 점수 39·40 → raw 평균 39.5)
-- 2026-08-17 재적합 앵커(a90=30.5455·a99=45.6667) 구간에서 39.0과 39.5 둘 다 hype_account_score=90으로
-- 반올림 동점 — 실 DB 함수로 역산해 고정한 값(analytics.hype_score('feed', NULL, likes, 0, 999000, ~0)으로 확인).
-- (댓글 가중 무관 — comments=0이므로 이 픽스처는 콘텐츠 Q 앵커·계정 앵커 재적합에만 반응해 값이 이동했다.)
-- handle 알파벳순은 dummy_alpha가 dummy_zeta보다 앞이라, 구코드(avg_hype_score DESC, handle ASC)라면
-- dummy_alpha가 먼저 나왔을 것 — 이 테스트가 바로 그 재발을 잡는다(단조성 단언만으론 못 잡던 부분).
INSERT INTO influencer(id, username, status, followers, beauty, beauty_company, beauty_judged_at) VALUES
 (99990041, 'dummy_alpha', 'QUALIFIED', 999000, true, false, timestamptz '2026-06-01 00:00:00+09'),
 (99990042, 'dummy_zeta',  'QUALIFIED', 999000, true, false, timestamptz '2026-06-01 00:00:00+09');
INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at,
                    status, first_seen_at, origin, collect_attempts) VALUES
 (99990141, 'dummy_al1', 'FEED', 'dummy_alpha', 99990041, now() - interval '1 hour',
  'PENDING', now() - interval '1 hour', 'ENUMERATION', 0),
 (99990142, 'dummy_al2', 'FEED', 'dummy_alpha', 99990041, now() - interval '2 hours',
  'PENDING', now() - interval '2 hours', 'ENUMERATION', 0),
 (99990143, 'dummy_zt1', 'FEED', 'dummy_zeta', 99990042, now() - interval '1 hour',
  'PENDING', now() - interval '1 hour', 'ENUMERATION', 0),
 (99990144, 'dummy_zt2', 'FEED', 'dummy_zeta', 99990042, now() - interval '2 hours',
  'PENDING', now() - interval '2 hours', 'ENUMERATION', 0);
INSERT INTO raw_profile(influencer_id, crawl_run_id, source, username, followers, payload, captured_at) VALUES
 (99990041, 99990000, 'HIKER_MOBILE', 'dummy_alpha', 999000,
  '{"status":"ok","user":{"username":"dummy_alpha","follower_count":999000}}'::jsonb, now()),
 (99990042, 99990000, 'HIKER_MOBILE', 'dummy_zeta', 999000,
  '{"status":"ok","user":{"username":"dummy_zeta","follower_count":999000}}'::jsonb, now());
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at) VALUES
 (99990041, 99990000, 'HIKER_V2_CLIPS',
  '{"response":{"status":"ok","items":[{"media":{"code":"dummy_al1","product_type":"clips","taken_at":1780272000,"like_count":16400,"comment_count":0,"caption":{"text":"cap al1"}}}]}}'::jsonb, now()),
 (99990041, 99990000, 'HIKER_V2_CLIPS',
  '{"response":{"status":"ok","items":[{"media":{"code":"dummy_al2","product_type":"clips","taken_at":1780272000,"like_count":17800,"comment_count":0,"caption":{"text":"cap al2"}}}]}}'::jsonb, now()),
 (99990042, 99990000, 'HIKER_V2_CLIPS',
  '{"response":{"status":"ok","items":[{"media":{"code":"dummy_zt1","product_type":"clips","taken_at":1780272000,"like_count":17100,"comment_count":0,"caption":{"text":"cap zt1"}}}]}}'::jsonb, now()),
 (99990042, 99990000, 'HIKER_V2_CLIPS',
  '{"response":{"status":"ok","items":[{"media":{"code":"dummy_zt2","product_type":"clips","taken_at":1780272000,"like_count":17800,"comment_count":0,"caption":{"text":"cap zt2"}}}]}}'::jsonb, now());
SELECT analytics.refresh_snapshot_cache();

DO $$
DECLARE
  raw_alpha numeric; raw_zeta numeric; score_alpha bigint; score_zeta bigint;
  ordered text[];
BEGIN
  SELECT avg_hype_raw, avg_hype_score INTO raw_alpha, score_alpha
  FROM analytics.v_account_summaries WHERE handle = 'dummy_alpha';
  SELECT avg_hype_raw, avg_hype_score INTO raw_zeta, score_zeta
  FROM analytics.v_account_summaries WHERE handle = 'dummy_zeta';

  ASSERT raw_alpha IS NOT NULL AND raw_zeta IS NOT NULL,
    '동점 픽스처 raw가 NULL (전제 붕괴)';
  ASSERT score_alpha = score_zeta,
    format('동점 전제 무효: dummy_alpha avg_hype_score=%s, dummy_zeta avg_hype_score=%s (동점이어야 회귀 테스트가 성립)',
           score_alpha, score_zeta);
  ASSERT raw_zeta > raw_alpha,
    format('raw 전제 무효: dummy_zeta raw=%s가 dummy_alpha raw=%s보다 커야 함', raw_zeta, raw_alpha);

  SELECT array_agg(handle ORDER BY avg_hype_raw DESC NULLS LAST, handle)
  INTO ordered
  FROM analytics.v_account_summaries
  WHERE handle IN ('dummy_alpha', 'dummy_zeta');

  IF ordered <> ARRAY['dummy_zeta', 'dummy_alpha'] THEN
    RAISE EXCEPTION 'avg_hype_raw DESC 정렬이 raw 순서를 따르지 않음(알파벳순 지배 재발): %', ordered;
  END IF;

  RAISE NOTICE '10_account_score_rescale (정렬 키 분리): 모든 단언 통과';
END $$;

-- 계정 소수점 매핑 함수(analytics.hype_account_score_precise) 회귀 — 콘텐츠 출력 매핑 도입에 따른
-- 계정 앵커 재적합, 스펙 2026-07-30-hype-score-v3-decay-after-mapping-design.md §10.
-- 순수 함수 검증 — hype_account_score()(정수, 값·의미 불변)와는 완전히 독립된 새 앵커·새 입력
-- 기준량이라 여기 단언은 위 hype_account_score 단언과 겹치지 않는다.
DO $$
DECLARE
  s1 numeric; s2 numeric; s3 numeric; s4 numeric; top numeric;
  a05 numeric; a50 numeric; a90 numeric; a99 numeric;
  base numeric;
BEGIN
  -- 1) 단조성: 기본 앵커(p05=1.3665·p50=26.6730·p90=66.6060·p99=85.2125, 2026-08-17 재적합 — 댓글
  --    가중 1.5 기준 운영 코퍼스 실측, 모수 n=4,583) 5개 구간을 고루 지나는 표본 — 샘플 값
  --    (1·10·40·65·90)은 단조성만 확인하므로 구간 이동과 무관하게 그대로 재사용한다(s4=65는 신
  --    앵커에서 a50~a90 구간으로 이동했지만 단조 순서 자체는 어느 구간이든 성립).
  s1  := analytics.hype_account_score_precise(1);    -- < a05
  s2  := analytics.hype_account_score_precise(10);   -- a05~a50
  s3  := analytics.hype_account_score_precise(40);   -- a50~a90
  s4  := analytics.hype_account_score_precise(65);   -- a50~a90
  top := analytics.hype_account_score_precise(90);   -- > a99 (초과구간)
  IF NOT (s1 <= s2 AND s2 <= s3 AND s3 <= s4 AND s4 <= top) THEN
    RAISE EXCEPTION '단조성 위반: %, %, %, %, %', s1, s2, s3, s4, top;
  END IF;

  -- 2) 앵커점 매핑: 기본 앵커 4점이 각각 10·45·80·97로 정확히 잡힌다.
  --    앵커값은 2026-08-17 재적합(댓글 가중 1.5 기준 운영 코퍼스 실측, 스펙
  --    docs/superpowers/specs/2026-08-17-hype-comment-weight-design.md) —
  --    구값(1.2417/19.4383/52.2401/74.0179)에서 갱신됨.
  a05 := analytics.hype_account_score_precise(1.3665);
  a50 := analytics.hype_account_score_precise(26.6730);
  a90 := analytics.hype_account_score_precise(66.6060);
  a99 := analytics.hype_account_score_precise(85.2125);
  ASSERT a05 = 10, format('p05 앵커점 불일치: %s (기대 10)', a05);
  ASSERT a50 = 45, format('p50 앵커점 불일치: %s (기대 45)', a50);
  ASSERT a90 = 80, format('p90 앵커점 불일치: %s (기대 80)', a90);
  ASSERT a99 = 97, format('p99 앵커점 불일치: %s (기대 97)', a99);

  -- 3) [0,100] 클램프 + 0 보존.
  ASSERT analytics.hype_account_score_precise(1000) BETWEEN 0 AND 100, '상한 클램프 위반';
  ASSERT analytics.hype_account_score_precise(0) = 0, 'raw=0 보존 실패';

  -- 4) NULL 보존: 창 전체 점수 불가(raw NULL) 계정은 여전히 NULL.
  ASSERT analytics.hype_account_score_precise(NULL) IS NULL, 'raw=NULL 보존 실패';

  -- 5) 앵커 오버라이드 반응 — 재배포 없는 튜닝 가능(hype_account_score·hype_score_output과 동일 관용구).
  base := analytics.hype_account_score_precise(40);
  INSERT INTO app_setting(key,value) VALUES
    ('analytics.hype-anchor-acct-precise-p50','60'),
    ('analytics.hype-anchor-acct-precise-p90','80'),
    ('analytics.hype-anchor-acct-precise-p99','95');
  ASSERT analytics.hype_account_score_precise(40) < base,
    format('앵커 오버라이드 무반응: base=%s, p50↑ 후=%s (더 낮아야 함)', base, analytics.hype_account_score_precise(40));
  DELETE FROM app_setting WHERE key LIKE 'analytics.hype-anchor-acct-precise-%';

  RAISE NOTICE '10_account_score_precise: 모든 단언 통과';
END $$;

-- 핵심 회귀: avg_hype_score(정수)가 동점인 dummy_alpha·dummy_zeta(위에서 이미 검증한 픽스처, 두
-- 계정 다 avg_hype_score=90)라도 avg_hype_score_precise(소수, 콘텐츠 출력 매핑 반영 창 평균)는
-- 갈려야 한다 — dummy_zeta의 게시물 좋아요(17100·17800)가 dummy_alpha(16400·17800)보다 앞쪽이
-- 크거나 같아 창 평균이 항상 높으므로, 단조 매핑을 거쳐도 순서가 보존돼야 한다.
DO $$
DECLARE precise_alpha numeric; precise_zeta numeric; score_alpha bigint; score_zeta bigint;
BEGIN
  SELECT avg_hype_score, avg_hype_score_precise INTO score_alpha, precise_alpha
  FROM analytics.v_account_summaries WHERE handle = 'dummy_alpha';
  SELECT avg_hype_score, avg_hype_score_precise INTO score_zeta, precise_zeta
  FROM analytics.v_account_summaries WHERE handle = 'dummy_zeta';

  ASSERT score_alpha = score_zeta,
    format('동점 전제 무효: avg_hype_score alpha=%s, zeta=%s (동점이어야 회귀 테스트가 성립)',
           score_alpha, score_zeta);
  ASSERT precise_alpha IS NOT NULL AND precise_zeta IS NOT NULL,
    'avg_hype_score_precise가 NULL (전제 붕괴)';
  ASSERT precise_zeta > precise_alpha,
    format('avg_hype_score_precise가 정수 동점을 못 갈랐음: alpha=%s, zeta=%s (zeta가 더 커야 함)',
           precise_alpha, precise_zeta);

  RAISE NOTICE '10_account_score_precise (동점 제거 회귀): 모든 단언 통과';
END $$;

-- =====================================================================================================
-- 고정 분모 회귀 (2026-07-31, 스펙 2026-07-31-account-score-fixed-denominator-design.md).
-- 배경: avg_hype_precise_raw(10_account_detail.sql base CTE)가 avg()(분모=analyzed_count)에서
-- sum()/analytics.recent-window(분모=창 크기 고정)로 바뀌었다 — 창이 12로 꽉 찼는데도 likes/comments
-- 수집 누락으로 점수산출 콘텐츠가 1~2건뿐인 계정이 그 1~2건의 avg만으로 12건 채운 계정과 동일하게
-- 평가되던 결함(test 스택 실측: ynp.ny 2건 7위·sunyvvin 1건 8위·zero_lyrical 1건 12위)을 해소한다.
--
-- 픽스처: dummy_fixed1(FEED 1건)과 dummy_fixed12(FEED 12건) — **개별 콘텐츠 점수를 완전히 동일하게**
-- 만든다(같은 followers·likes·comments·content_type·uploaded_at). 이러면 avg()로는 두 계정의
-- avg_hype_precise_raw가 항등(콘텐츠 1건짜리 평균 = 그 값 자체, 12건 동일값 평균도 그 값 자체)이라
-- avg_hype_score_precise도 완전히 같아진다 — 이것이 바로 이번에 고친 결함이다.
--
-- 구 코드(avg) 회귀 여부는 이 테스트를 추가하기 전에 실 DB에서 직접 확인했다(수동 SQL, 이 파일 밖):
--   old_raw_avg(둘 다 avg 기준) = 66.3988274056849023989769330566531719988167 (dummy_fixed1·dummy_fixed12 동일)
--   → hype_account_score_precise(66.3988...) = 91.0525 (둘 다 동일 — 구 코드라면 이 테스트가 실패했을 조건)
-- 신 코드(sum/12)에서는 dummy_fixed1 raw = 66.3988.../12 = 5.5332..., dummy_fixed12 raw = 66.3988...
-- (12건이라 sum/12=sum/count=avg와 항등) → avg_hype_score_precise가 각각 약 18.25 / 91.05로 뚜렷이 갈린다.
INSERT INTO influencer(id, username, status, followers, beauty, beauty_company, beauty_judged_at) VALUES
 (99990050, 'dummy_fixed1',  'QUALIFIED', 999000, true, false, timestamptz '2026-06-01 00:00:00+09'),
 (99990051, 'dummy_fixed12', 'QUALIFIED', 999000, true, false, timestamptz '2026-06-01 00:00:00+09');
INSERT INTO raw_profile(influencer_id, crawl_run_id, source, username, followers, payload, captured_at) VALUES
 (99990050, 99990000, 'HIKER_MOBILE', 'dummy_fixed1', 999000,
  '{"status":"ok","user":{"username":"dummy_fixed1","follower_count":999000}}'::jsonb, now()),
 (99990051, 99990000, 'HIKER_MOBILE', 'dummy_fixed12', 999000,
  '{"status":"ok","user":{"username":"dummy_fixed12","follower_count":999000}}'::jsonb, now());
INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at,
                    status, first_seen_at, origin, collect_attempts)
VALUES (99990150, 'dummy_fx1_1', 'FEED', 'dummy_fixed1', 99990050, now() - interval '1 hour',
        'PENDING', now() - interval '1 hour', 'ENUMERATION', 0);
INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at)
VALUES (99990050, 99990000, 'HIKER_V2_CLIPS',
  '{"response":{"status":"ok","items":[{"media":{"code":"dummy_fx1_1","product_type":"clips","taken_at":1780272000,"like_count":17800,"comment_count":0,"caption":{"text":"cap fx1"}}}]}}'::jsonb, now());
-- dummy_fixed12: 위와 완전히 동일한 likes·comments·followers·uploaded_at을 가진 게시물 12개
-- (recent-window 기본값 12와 맞춰 창을 정확히 채운다 — 회귀의 전제인 "창이 12로 꽉 찬 계정" 재현).
DO $do$
DECLARE i int;
BEGIN
  FOR i IN 1..12 LOOP
    INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at,
                        status, first_seen_at, origin, collect_attempts)
    VALUES (99990250 + i, 'dummy_fx12_' || i, 'FEED', 'dummy_fixed12', 99990051,
            now() - interval '1 hour', 'PENDING', now() - interval '1 hour', 'ENUMERATION', 0);
    INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at)
    VALUES (99990051, 99990000, 'HIKER_V2_CLIPS',
      format('{"response":{"status":"ok","items":[{"media":{"code":"%s","product_type":"clips","taken_at":1780272000,"like_count":17800,"comment_count":0,"caption":{"text":"cap fx12"}}}]}}',
             'dummy_fx12_' || i)::jsonb, now());
  END LOOP;
END $do$;
SELECT analytics.refresh_snapshot_cache();

DO $$
DECLARE
  precise_fixed1 numeric; precise_fixed12 numeric;
  n1 int; n12 int;
BEGIN
  SELECT count(*) INTO n1  FROM analytics.v_account_recent WHERE owner_username = 'dummy_fixed1';
  SELECT count(*) INTO n12 FROM analytics.v_account_recent WHERE owner_username = 'dummy_fixed12';
  ASSERT n1 = 1,  format('dummy_fixed1 창 크기 전제 붕괴: %s (기대 1)', n1);
  ASSERT n12 = 12, format('dummy_fixed12 창 크기 전제 붕괴: %s (기대 12 — recent-window 기본값과 일치해야 함)', n12);

  SELECT avg_hype_score_precise INTO precise_fixed1
  FROM analytics.v_account_summaries WHERE handle = 'dummy_fixed1';
  SELECT avg_hype_score_precise INTO precise_fixed12
  FROM analytics.v_account_summaries WHERE handle = 'dummy_fixed12';

  ASSERT precise_fixed1 IS NOT NULL AND precise_fixed12 IS NOT NULL,
    'dummy_fixed1/dummy_fixed12 avg_hype_score_precise가 NULL (전제 붕괴)';

  -- 핵심 회귀: 같은 개별 콘텐츠 점수인데 점수산출 콘텐츠 1건뿐인 계정이 12건 채운 계정보다
  -- 뚜렷하게 낮아야 한다. 실측 비율 약 4.99배(18.2545 vs 91.0525) — 여유를 두고 3배로 단언해
  -- 향후 앵커 재적합으로 절대값이 흔들려도 "1건 vs 12건" 구조적 격차 자체는 계속 잡는다.
  ASSERT precise_fixed12 > precise_fixed1 * 3,
    format('고정 분모 회귀 실패: dummy_fixed1=%s, dummy_fixed12=%s (12건이 1건의 3배 이상 높아야 함 — '
           || '구 코드(avg)라면 두 값이 완전히 같았을 자리)', precise_fixed1, precise_fixed12);

  RAISE NOTICE '10_account_score_precise (고정 분모 회귀): 모든 단언 통과 (fixed1=%, fixed12=%)',
    precise_fixed1, precise_fixed12;
END $$;

-- =====================================================================================================
-- 0점 보존 (고정 분모 도입으로 비활동 계정이 0에서 들려 올라가지 않는지 확인).
-- dummy_zero12: 창이 12로 꽉 찬 FEED 계정, 전 게시물 likes=0·comments=0 → 콘텐츠별 정밀 점수가
-- 정확히 0(Q=ln(1+0/f0)=ln(1)=0 → 출력 매핑도 0) → sum=0 → 0/12=0 → 매핑 함수가 0을 0으로 통과.
-- sum()이 NULL을 무시하는 것과 별개로, 0점 콘텐츠는 NULL이 아니라 실제 0이 분자에 더해진다는 것과
-- 고정 분모가 그 0을 다른 값으로 밀어올리지 않는다는 것을 함께 확인한다.
INSERT INTO influencer(id, username, status, followers, beauty, beauty_company, beauty_judged_at)
VALUES (99990052, 'dummy_zero12', 'QUALIFIED', 999000, true, false, timestamptz '2026-06-01 00:00:00+09');
INSERT INTO raw_profile(influencer_id, crawl_run_id, source, username, followers, payload, captured_at)
VALUES (99990052, 99990000, 'HIKER_MOBILE', 'dummy_zero12', 999000,
  '{"status":"ok","user":{"username":"dummy_zero12","follower_count":999000}}'::jsonb, now());
DO $do$
DECLARE i int;
BEGIN
  FOR i IN 1..12 LOOP
    INSERT INTO content(id, short_code, content_type, owner_username, influencer_id, uploaded_at,
                        status, first_seen_at, origin, collect_attempts)
    VALUES (99990163 + i, 'dummy_z12_' || i, 'FEED', 'dummy_zero12', 99990052,
            now() - interval '1 hour', 'PENDING', now() - interval '1 hour', 'ENUMERATION', 0);
    INSERT INTO raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at)
    VALUES (99990052, 99990000, 'HIKER_V2_CLIPS',
      format('{"response":{"status":"ok","items":[{"media":{"code":"%s","product_type":"clips","taken_at":1780272000,"like_count":0,"comment_count":0,"caption":{"text":"cap z12"}}}]}}',
             'dummy_z12_' || i)::jsonb, now());
  END LOOP;
END $do$;
SELECT analytics.refresh_snapshot_cache();

DO $$
DECLARE precise_zero numeric; n int;
BEGIN
  SELECT count(*) INTO n FROM analytics.v_account_recent WHERE owner_username = 'dummy_zero12';
  ASSERT n = 12, format('dummy_zero12 창 크기 전제 붕괴: %s (기대 12)', n);

  SELECT avg_hype_score_precise INTO precise_zero
  FROM analytics.v_account_summaries WHERE handle = 'dummy_zero12';

  ASSERT precise_zero IS NOT NULL, 'dummy_zero12 avg_hype_score_precise가 NULL (0점과 NULL을 혼동하면 안 됨)';
  ASSERT precise_zero = 0,
    format('0점 보존 실패: dummy_zero12 avg_hype_score_precise=%s (기대 0 — 고정 분모가 0을 다른 값으로 밀어올림)',
           precise_zero);

  RAISE NOTICE '10_account_score_precise (0점 보존 회귀): 모든 단언 통과';
END $$;
