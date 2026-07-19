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

-- 증분 창(07-19): 창을 14일로 되돌리면 고정 날짜(2026-06) 시드는 전부 창 밖 → 후보 0.
-- (rn은 미숙성, 나머지는 창 초과 — 백필 MVP 제외 정책의 뷰 반영 검증)
UPDATE app_setting SET value = '14' WHERE key = 'analytics.analyze-window-days';
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM analytics.v_analysis_candidates WHERE account_handle LIKE 'dummy_%') = 0,
    '증분 창 밖 시드가 후보에 남아 있음';
END $$;
