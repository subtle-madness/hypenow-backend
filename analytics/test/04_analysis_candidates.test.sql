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

-- 제때 크롤 가드(07-19 재정정): 백필 판정은 게시물 나이가 아니라 고정 지표 스냅샷 시점 —
-- metric_captured_at ∈ [posted_at + pin(3), posted_at + pin + slack(2)). 시드는 날짜가 전부
-- 고정이라 상대 판정이 시간이 지나도 안정적이다. 여유를 1일로 좁히면 r1(핀 +4일3h)·r2(+4일3h)는
-- 늦크롤 판정 → 제외, f1(+3일3h)만 잔존.
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
