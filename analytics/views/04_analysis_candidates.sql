-- LLM 캡션 선분석 후보 (분석 잡 전용 — 미러 안 함). 스펙 2026-07-17 §5.
-- raw만 보고 판단 가능한 자격까지만 뷰가 담당: 뷰티 모수 ∩ ENUMERATION ∩ 캡션 존재 ∩
-- 숙성(uploaded_at + 'analytics.analyze-maturity-days'(기본 3)일 경과 — B4 가드 키 승계) ∩
-- 제때 크롤(07-19 재정정: 백필 판정은 게시물 나이가 아니라 고정 지표 스냅샷 시점 —
-- metric_captured_at ∈ [posted_at + pin(기본 3), posted_at + pin + 'analytics.analyze-timely-slack-days'(기본 2)).
-- 늦크롤 백필(+3일 지표 없음)과 미성숙 폴백 지표를 함께 제외. 분석 밀림은 나이 무관 재대상).
-- '이미 분석됨' 제외(analysis DB content_analyses 대조)·배치 상한·정렬 정책은 Java 몫 —
-- Haiku+Batch 파이프라인(별도 설계)이 이 뷰를 입구로 배치를 구성한다.
-- v_contents 위에 얹는다: 모수·+3일 고정 지표·최신 메타(캡션·썸네일) 규칙을 그대로 승계.
CREATE OR REPLACE VIEW analytics.v_analysis_candidates AS
SELECT
  v.short_code,
  v.content_type,
  v.account_handle,
  v.posted_at AS uploaded_at,
  v.caption,
  v.thumbnail_url,
  pr.followers,
  v.views,
  v.likes,
  v.comments,
  v.metric_captured_at
FROM analytics.v_contents v
LEFT JOIN analytics.v_base_profile pr ON pr.username = v.account_handle
WHERE v.caption IS NOT NULL AND btrim(v.caption) <> ''
  AND v.posted_at + make_interval(days => COALESCE(
        (SELECT value::int FROM app_setting WHERE key = 'analytics.analyze-maturity-days'), 3)) <= now()
  AND v.metric_captured_at >= v.posted_at + make_interval(days => COALESCE(
        (SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3))
  AND v.metric_captured_at < v.posted_at + make_interval(days => COALESCE(
        (SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3)
      + COALESCE(
        (SELECT value::int FROM app_setting WHERE key = 'analytics.analyze-timely-slack-days'), 2));
