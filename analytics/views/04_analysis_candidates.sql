-- LLM 캡션 선분석 후보 (분석 잡 전용 — 미러 안 함). 스펙 2026-07-17 §5.
-- raw만 보고 판단 가능한 자격까지만 뷰가 담당: 뷰티 모수 ∩ ENUMERATION ∩ 캡션 존재 ∩
-- 숙성(uploaded_at + 'analytics.analyze-maturity-days'(기본 3)일 경과 — B4 가드 키 승계).
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
        (SELECT value::int FROM app_setting WHERE key = 'analytics.analyze-maturity-days'), 3)) <= now();
