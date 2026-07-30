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
  --    기본 앵커(p05=1.0833·p50=12.8333·p90=31.2000·p99=44.86) 5개 구간을 고루 지나는 표본.
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
END $$;

-- 핵심 회귀: 표시 점수가 동점인 두 계정을 만들어 정렬이 raw 순서를 따르는지 확인한다.
-- dummy_alpha(피드 2건, likes 16400·17800 → 콘텐츠 점수 34·36 → raw 평균 35.0)
-- dummy_zeta (피드 2건, likes 17100·17800 → 콘텐츠 점수 35·36 → raw 평균 35.5)
-- 기본 계정 앵커(a90=31.2·a99=44.86) 구간에서 35.0과 35.5 둘 다 hype_account_score=85로 반올림 동점—
-- 실 DB 함수로 역산해 고정한 값(analytics.hype_score('feed', NULL, likes, 0, 999000, ~0)으로 확인).
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
