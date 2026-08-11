-- 서빙 뷰 기대값 — 미러 계약 형태(컬럼 이름·순서)는 구 버전과 동일해야 한다.
DO $$
BEGIN
  -- v_accounts: 뷰티 인플루언서 ∩ 프로필 보유
  ASSERT (SELECT count(*) FROM analytics.v_accounts WHERE handle LIKE 'dummy_%') = 2,
    'v_accounts dummy rows != 2 (a·b만)';
  ASSERT EXISTS (SELECT 1 FROM analytics.v_accounts WHERE handle = 'dummy_a' AND followers = 5500),
    'v_accounts dummy_a followers != 5500';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_accounts WHERE handle IN ('dummy_co','dummy_x','dummy_e')),
    'v_accounts에 모수 제외 대상 존재';

  -- v_contents: +3일 고정(성숙 최이른) + 최신 메타 + 피드 NULL
  ASSERT (SELECT count(*) FROM analytics.v_contents WHERE account_handle LIKE 'dummy_%') = 6,
    'v_contents dummy rows != 6 (+ra1(액터))';
  ASSERT (SELECT views FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 11000,
    'v_contents r1 views != 11000 (06-05 성숙 최이른 스냅샷 고정)';
  ASSERT (SELECT likes FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 520,
    'v_contents r1 likes != 520 (고정 스냅샷)';
  ASSERT (SELECT caption FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 'cap r1 v3',
    'v_contents r1 caption != cap r1 v3 (메타는 최신)';
  ASSERT (SELECT thumbnail_url FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 'https://thumb/r1_v3.jpg',
    'v_contents r1 thumbnail != 최신 스냅샷';
  ASSERT (SELECT views FROM analytics.v_contents WHERE short_code = 'dummy_rn') = 100,
    'v_contents rn views != 100 (성숙 스냅샷 없음 → 최신 폴백)';
  ASSERT (SELECT views FROM analytics.v_contents WHERE short_code = 'dummy_f1') IS NULL,
    'v_contents f1 views not null (피드)';
  ASSERT (SELECT original_url FROM analytics.v_contents WHERE short_code = 'dummy_r1')
         = 'https://www.instagram.com/p/dummy_r1/',
    'v_contents r1 original_url mismatch (short_code 합성)';
  ASSERT (SELECT content_type FROM analytics.v_contents WHERE short_code = 'dummy_r1') = 'reels',
    'v_contents r1 content_type != reels (lower)';
  ASSERT (SELECT hype_score FROM analytics.v_contents WHERE short_code = 'dummy_r1') IS NOT NULL,
    'v_contents r1 hype_score is null';
  -- hype_score_precise(2026-07-30, 스펙 §10) — 신설 컬럼. 피드 dummy_f1도 views NULL 규약이
  -- 정상이라 precise도 hype_score와 마찬가지로 NULL이 아니어야 한다.
  ASSERT (SELECT hype_score_precise FROM analytics.v_contents WHERE short_code = 'dummy_r1') IS NOT NULL,
    'v_contents r1 hype_score_precise is null';
  ASSERT (SELECT hype_score_precise FROM analytics.v_contents WHERE short_code = 'dummy_f1') IS NOT NULL,
    'v_contents f1(피드) hype_score_precise is null (views NULL은 피드 정상 — CLAUDE.md 함정)';
  ASSERT (SELECT hype_score_precise FROM analytics.v_contents WHERE short_code = 'dummy_r1') BETWEEN 0 AND 100,
    'v_contents r1 hype_score_precise가 [0,100] 밖';
  -- 인스타 유료 파트너십 태그가 핀 스냅샷에서 v_contents까지 통과한다 (07-21 — LLM 프롬프트 입력용,
  -- 미러 contents.ad_marked로 내려가 일상 잡이 확정 사실로 싣는다). 피드는 소스가 항상 false.
  ASSERT (SELECT ad_marked FROM analytics.v_contents WHERE short_code = 'dummy_r1') = false,
    'v_contents r1 ad_marked != false';
  ASSERT (SELECT ad_marked FROM analytics.v_contents WHERE short_code = 'dummy_f1') = false,
    'v_contents f1(피드) ad_marked != false';

  -- v_content_metric_snapshots: 이력 + 합성 id + 모수
  ASSERT (SELECT count(*) FROM analytics.v_content_metric_snapshots WHERE short_code = 'dummy_r1') = 4,
    'v_content_metric_snapshots r1 rows != 4';
  ASSERT (SELECT count(*) = count(DISTINCT id) FROM analytics.v_content_metric_snapshots
          WHERE short_code LIKE 'dummy_%'),
    'v_content_metric_snapshots 합성 id 중복';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_content_metric_snapshots
                     WHERE short_code IN ('dummy_d1','dummy_r4','dummy_r5')),
    'v_content_metric_snapshots에 모수 제외 대상 존재';

  -- v_content_comments: 마스킹
  ASSERT (SELECT count(*) FROM analytics.v_content_comments WHERE short_code = 'dummy_r1') = 3,
    'v_content_comments r1 rows != 3';
  ASSERT (SELECT count(*) FROM analytics.v_content_comments
          WHERE short_code = 'dummy_r1' AND author_masked = 'dum***') = 3,
    'v_content_comments author_masked != dum***';
END $$;

-- hype_score v3 (Q 기준 앵커·매핑 후 감쇠) — 인자 순서 (type, views, likes, comments, followers, elapsed_days)
--   튜닝 상수는 함수가 app_setting에서 직접 읽음(STABLE). 시드가 analytics.% 키를 지워 기본값으로 시작.
DO $$
DECLARE lo bigint; hi bigint; mid bigint; base bigint; v_old numeric; v_new numeric;
BEGIN
  -- NULL 규칙
  ASSERT analytics.hype_score('reels', NULL, 100, 10, 5000, 3) IS NULL, '릴스 views NULL → NULL';
  ASSERT analytics.hype_score('reels', 10000, NULL, 10, 5000, 3) IS NULL, 'likes NULL → NULL';
  ASSERT analytics.hype_score('reels', 10000, 100, NULL, 5000, 3) IS NULL, 'comments NULL → NULL';
  ASSERT analytics.hype_score('feed', NULL, 100, 10, 5000, 3) IS NOT NULL, '피드 views NULL 정상';

  -- 결과 [0,100] 클램프
  ASSERT analytics.hype_score('reels', 5000000, 50000, 5000, 3000, 1) BETWEEN 0 AND 100, '상한 100';
  ASSERT analytics.hype_score('reels', 10, 0, 0, 100000, 60) BETWEEN 0 AND 100, '하한 0';

  -- 하드캡 없음 ①: 참여 ↑ → 점수 ↑ (도달·팔로워 고정)
  lo := analytics.hype_score('reels', 50000, 500, 10, 10000, 3);
  hi := analytics.hype_score('reels', 50000, 5000, 200, 10000, 3);
  ASSERT hi > lo, format('참여 ↑ → 점수 ↑ (%s > %s)', hi, lo);

  -- 하드캡 없음 ②: 극단 도달도 계속 상승 (v1 하드캡이면 동일했을 것 — 참여율 고정 위해 지표 비례 확대)
  mid := analytics.hype_score('reels', 100000, 2000, 100, 10000, 3);
  hi  := analytics.hype_score('reels', 400000, 8000, 400, 10000, 3);
  ASSERT hi > mid, format('도달 ↑ → 점수 ↑ (%s > %s)', hi, mid);

  -- 신선도: 오래될수록 ↓
  ASSERT analytics.hype_score('reels', 50000, 1000, 50, 10000, 30)
       < analytics.hype_score('reels', 50000, 1000, 50, 10000, 3), '오래되면 점수 ↓';

  -- app_setting 반영 ①: 반감기 (함수가 내부에서 읽음)
  INSERT INTO app_setting(key,value) VALUES ('analytics.hype-fresh-halflife-days','1');
  lo := analytics.hype_score('reels', 50000, 1000, 50, 10000, 14);
  UPDATE app_setting SET value='100' WHERE key='analytics.hype-fresh-halflife-days';
  hi := analytics.hype_score('reels', 50000, 1000, 50, 10000, 14);
  ASSERT hi > lo, format('반감기 100(%s) > 1(%s)', hi, lo);
  DELETE FROM app_setting WHERE key='analytics.hype-fresh-halflife-days';

  -- app_setting 반영 ②: 앵커 (릴스 p50·p90·p99를 크게 올리면 같은 Q가 더 낮은 점수)
  base := analytics.hype_score('reels', 50000, 1000, 50, 10000, 3);
  INSERT INTO app_setting(key,value) VALUES
    ('analytics.hype-anchor-q-reels-p50','5.0'),('analytics.hype-anchor-q-reels-p90','8.0'),('analytics.hype-anchor-q-reels-p99','12.0');
  ASSERT analytics.hype_score('reels', 50000, 1000, 50, 10000, 3) < base, '앵커 p50↑ → 같은 Q 더 낮은 점수';
  DELETE FROM app_setting WHERE key LIKE 'analytics.hype-anchor-%';

  -- app_setting 반영 ③: engage 가중치 ↑ → 고참여 콘텐츠 점수 ↑
  base := analytics.hype_score('reels', 50000, 5000, 200, 10000, 3);
  INSERT INTO app_setting(key,value) VALUES ('analytics.hype-engage-weight','3');
  ASSERT analytics.hype_score('reels', 50000, 5000, 200, 10000, 3) > base, 'engage-weight ↑ → 고참여 점수 ↑';
  DELETE FROM app_setting WHERE key = 'analytics.hype-engage-weight';

  -- 뷰: v_content_metric_snapshots가 app_setting을 읽어 반영(반감기 ↑ → dummy_r1 점수 합 ↑)
  INSERT INTO app_setting(key, value) VALUES ('analytics.hype-fresh-halflife-days', '7');
  v_old := (SELECT sum(hype_score) FROM analytics.v_content_metric_snapshots WHERE short_code = 'dummy_r1');
  UPDATE app_setting SET value = '100' WHERE key = 'analytics.hype-fresh-halflife-days';
  v_new := (SELECT sum(hype_score) FROM analytics.v_content_metric_snapshots WHERE short_code = 'dummy_r1');
  ASSERT v_new > v_old, format('뷰 반감기 100(%s) > 7(%s)', v_new, v_old);
END $$;

-- v_contents 일관성 회귀(2026-07-30, 스펙 §10): hype_score(구, 값·의미 불변)와 hype_score_precise
-- (신)가 반드시 "같은 base 행"에서 파생돼야 한다 — dummy_r1의 실제 지표를 그대로 함수에 다시
-- 넣어(now()는 트랜잭션 내에서 안정값이라 뷰가 쓴 것과 동일) 두 컬럼을 독립 재계산·대조한다.
DO $$
DECLARE
  v_ct text; v_views bigint; v_likes bigint; v_comments bigint; v_followers bigint; v_uploaded timestamptz;
  v_hype bigint; v_precise numeric;
  expected_raw numeric; expected_precise numeric;
BEGIN
  SELECT e.content_type, p.views, p.likes, p.comments_count, pr.followers, e.uploaded_at
  INTO v_ct, v_views, v_likes, v_comments, v_followers, v_uploaded
  FROM analytics.v_serving_content e
  JOIN analytics.v_pinned_metrics p USING (content_id)
  LEFT JOIN analytics.v_base_profile pr ON pr.username = e.owner_username
  WHERE e.short_code = 'dummy_r1';

  SELECT hype_score, hype_score_precise INTO v_hype, v_precise
  FROM analytics.v_contents WHERE short_code = 'dummy_r1';

  expected_raw := analytics.hype_score_raw(lower(v_ct), v_views, v_likes, v_comments, v_followers,
                    extract(epoch FROM (now() - v_uploaded)) / 86400.0);
  expected_precise := round(analytics.hype_score_output(expected_raw), 4);

  ASSERT v_hype = round(expected_raw)::bigint,
    format('v_contents.hype_score(%s) != round(hype_score_raw(...))::bigint(%s) — 구 정수 값 불변 계약 깨짐',
           v_hype, round(expected_raw)::bigint);
  ASSERT v_precise = expected_precise,
    format('v_contents.hype_score_precise(%s) != round(hype_score_output(hype_score_raw(...)),4)(%s) — 신 컬럼이 구 컬럼과 다른 base에서 파생됨',
           v_precise, expected_precise);

  RAISE NOTICE '02_serving (hype_score_precise 일관성): 모든 단언 통과';
END $$;
