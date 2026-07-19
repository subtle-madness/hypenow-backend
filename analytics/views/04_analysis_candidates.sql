-- LLM 캡션 선분석 후보 (분석 잡 전용 — 미러 안 함). 스펙 2026-07-17 §5.
-- raw만 보고 판단 가능한 자격까지만 뷰가 담당: 뷰티 모수 ∩ ENUMERATION ∩ 캡션 존재 ∩
-- 숙성(uploaded_at + 'analytics.analyze-maturity-days'(기본 3)일 경과 — B4 가드 키 승계) ∩
-- 제때 크롤(백필 판정은 게시물 나이가 아니라 지표 스냅샷 시점).
-- '이미 분석됨' 제외(analysis DB content_analyses 대조)·배치 상한·정렬 정책은 Java 몫 —
-- Haiku+Batch 파이프라인(별도 설계)이 이 뷰를 입구로 배치를 구성한다.
-- v_contents 위에 얹는다: 모수·+3일 고정 지표·최신 메타(캡션·썸네일) 규칙을 그대로 승계.
--
-- 제때 크롤 가드 (07-19 재재정정): "고정 핀(v_contents.metric_captured_at)이 창 안"이 아니라
-- **원본 스냅샷 중 [posted+pin, posted+pin+slack)에 usable 스냅샷이 EXISTS** 로 판정한다.
-- 이유: 핀 선택 로직(02 pinned CTE, PR #58 usable 우선순위)은 시점·정책에 따라 옮겨다녀
-- 자격이 흔들렸다(같은 데이터인데 핀이 바뀌면 후보 여부가 뒤집힘). EXISTS는 (업로드시각,
-- 스냅샷 캡처시각)만의 함수라 핀 로직·잡 실행 시점과 무관하게 결정론적이다.
-- usable = 지표 완비(릴스는 views·likes·comments, 피드는 likes·comments — 피드 views 항상 NULL,
-- 02 서빙 뷰의 usable 정의와 동일). pin/slack 키는 02·B4와 동일 승계.
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
  AND EXISTS (
    -- 창 안에 usable 스냅샷이 실제로 존재하는가 (핀 무관·결정론)
    SELECT 1
    FROM analytics.v_serving_content sc
    JOIN analytics.v_base_content_snapshot s USING (content_id)
    WHERE sc.short_code = v.short_code
      AND s.captured_at >= v.posted_at + make_interval(days => COALESCE(
            (SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3))
      AND s.captured_at <  v.posted_at + make_interval(days => COALESCE(
            (SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3)
          + COALESCE(
            (SELECT value::int FROM app_setting WHERE key = 'analytics.analyze-timely-slack-days'), 2))
      AND s.likes IS NOT NULL AND s.comments_count IS NOT NULL
      AND (sc.content_type <> 'REELS' OR s.views IS NOT NULL)
  );
