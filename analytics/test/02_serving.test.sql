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
  ASSERT (SELECT count(*) FROM analytics.v_contents WHERE account_handle LIKE 'dummy_%') = 5,
    'v_contents dummy rows != 5';
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

-- hype_score v2 (연속 절대식·타입별 앵커) — 인자 순서 (type, views, likes, comments, followers, elapsed_days)
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

  -- app_setting 반영 ②: 앵커 (릴스 p50·p90·p99를 크게 올리면 같은 qf가 더 낮은 점수)
  base := analytics.hype_score('reels', 50000, 1000, 50, 10000, 3);
  INSERT INTO app_setting(key,value) VALUES
    ('analytics.hype-anchor-reels-p50','5.0'),('analytics.hype-anchor-reels-p90','8.0'),('analytics.hype-anchor-reels-p99','12.0');
  ASSERT analytics.hype_score('reels', 50000, 1000, 50, 10000, 3) < base, '앵커 p50↑ → 같은 qf 더 낮은 점수';
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
