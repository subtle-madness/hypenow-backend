-- LLM 캡션 선분석 후보 (분석 잡 전용 — 미러 안 함). 스펙 2026-07-17 §5.
-- raw만 보고 판단 가능한 자격까지만 뷰가 담당: 뷰티 모수 ∩ ENUMERATION ∩ 캡션 존재 ∩
-- 제때 크롤(백필 판정은 게시물 나이가 아니라 지표 스냅샷 시점).
-- '이미 분석됨' 제외(analysis DB content_analyses 대조)·배치 상한·정렬 정책은 Java 몫 —
-- Haiku/Gemini Batch 파이프라인이 이 뷰를 입구로 배치를 구성한다.
-- v_contents 위에 얹는다: 모수·고정 지표·최신 메타(캡션·썸네일) 규칙을 그대로 승계.
--
-- 제때 크롤 가드 (07-20 날짜기준 재정정): "시간 간격(72~96h)"이 아니라 **캡처 캘린더일(KST)이
-- 업로드 캘린더일 + pin(기본 3) ~ +pin+slack(기본 1)** 인가로 판정한다.
--   왜 날짜기준: 화면에 뜨는 건 업로드 '날짜'다. 시간 간격이면 저녁 업로드는 창이 다음날로 걸쳐
--   같은 날짜인데 자격 시점이 흔들리고("창이 아직 열림"), 한 크롤일이 두 업로드일을 물어 date-bleed가 났다.
--   날짜기준이면 업로드일 D는 크롤일 D+3 하나에만 대응 → 화면 날짜와 1:1, 확정적.
--   slack=1이면 D+3 '그 날' 하루, slack=2면 D+3·D+4 이틀. (핀 로직·잡 실행 시점과 무관 — 결정론)
-- 성숙(백필/미숙성 가드 통합): 제때창(D+pin ~ D+pin+slack)이 **완전히 지난 날**만 대상 —
--   그 날 크롤이 끝나야 스냅샷 유무가 확정되므로. 즉 D + pin + slack <= 오늘(KST).
--   (구 analytics.analyze-maturity-days 키는 이 조건에 흡수 — 더 이상 04에서 참조 안 함.)
-- usable = 지표 완비(릴스는 views·likes·comments, 피드는 likes·comments — 02 서빙 usable 정의와 동일).
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
  -- 성숙: 제때창이 완전히 지난 날만 (업로드일 + pin + slack <= 오늘 KST)
  AND (v.posted_at AT TIME ZONE 'Asia/Seoul')::date
        + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3)
        + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.analyze-timely-slack-days'), 1)
      <= (now() AT TIME ZONE 'Asia/Seoul')::date
  AND EXISTS (
    -- 캡처 캘린더일(KST)이 [업로드일+pin, 업로드일+pin+slack)에 드는 usable 스냅샷이 있는가.
    -- 성능: captured_at을 행마다 date로 변환하지 않고, 캘린더일 경계를 KST 자정 timestamptz로
    -- 계산해 captured_at을 그대로 범위 비교한다(sargable — 세미조인 플랜 유지, 스냅샷 뷰 1회 계산).
    -- 캡처가 KST일 X에 든다 ⟺ [KST자정(X), KST자정(X+1)) 이므로 결과는 날짜 변환과 완전 동치.
    SELECT 1
    FROM analytics.v_serving_content sc
    JOIN analytics.content_snapshot_cache s USING (content_id)
    WHERE sc.short_code = v.short_code
      AND s.captured_at >= (((v.posted_at AT TIME ZONE 'Asia/Seoul')::date
            + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3)
          )::timestamp AT TIME ZONE 'Asia/Seoul')
      AND s.captured_at <  (((v.posted_at AT TIME ZONE 'Asia/Seoul')::date
            + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3)
            + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.analyze-timely-slack-days'), 1)
          )::timestamp AT TIME ZONE 'Asia/Seoul')
      AND s.likes IS NOT NULL AND s.comments_count IS NOT NULL
      AND (sc.content_type <> 'REELS' OR s.views IS NOT NULL)
  );
