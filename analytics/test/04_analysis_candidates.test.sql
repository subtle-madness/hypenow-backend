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
