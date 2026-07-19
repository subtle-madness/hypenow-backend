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
