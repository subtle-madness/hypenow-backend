-- LLM 캡션 선분석 후보 (분석 잡 전용 — 미러 안 함). 스펙 2026-07-17 §5, 07-20 백필 재도입 개정.
-- raw만 보고 판단 가능한 자격까지만 뷰가 담당: 뷰티 모수 ∩ ENUMERATION ∩ 캡션 존재 ∩
-- 성숙(제때창이 완전히 지난 날) ∩ (제때 크롤 OR 최근 N개 윈도우 포함).
-- '이미 분석됨' 제외(analysis DB content_analyses 대조)·배치 상한·정렬 정책은 Java 몫 —
-- Haiku/Gemini Batch 파이프라인이 이 뷰를 입구로 배치를 구성한다.
-- v_contents 위에 얹는다: 모수·고정 지표·최신 메타(캡션·썸네일) 규칙을 그대로 승계.
--
-- 제때 크롤 가드 (07-20 날짜기준 재정정, 판정 로직 자체는 유지): "시간 간격(72~96h)"이 아니라
-- **캡처 캘린더일(KST)이 업로드 캘린더일 + pin(기본 3) ~ +pin+slack(기본 1)** 인가로 판정한다.
--   왜 날짜기준: 화면에 뜨는 건 업로드 '날짜'다. 시간 간격이면 저녁 업로드는 창이 다음날로 걸쳐
--   같은 날짜인데 자격 시점이 흔들리고("창이 아직 열림"), 한 크롤일이 두 업로드일을 물어 date-bleed가 났다.
--   날짜기준이면 업로드일 D는 크롤일 D+3 하나에만 대응 → 화면 날짜와 1:1, 확정적.
--   slack=1이면 D+3 '그 날' 하루, slack=2면 D+3·D+4 이틀. (핀 로직·잡 실행 시점과 무관 — 결정론)
-- 성숙(백필/미숙성 가드 통합): 제때창(D+pin ~ D+pin+slack)이 **완전히 지난 날**만 대상 —
--   그 날 크롤이 끝나야 스냅샷 유무가 확정되므로. 즉 D + pin + slack <= 오늘(KST).
--   이 성숙 가드는 아래 OR 백필 경로에도 그대로 적용된다 — 창이 아직 안 닫힌 콘텐츠는 제때/백필
--   여부 자체를 판정할 수 없어 최근 N 윈도우 안이라도 제외한다(예: 업로드 1일 전 rn).
--   (구 analytics.analyze-maturity-days 키는 이 조건에 흡수 — 04에서 참조 안 함.)
-- usable = 지표 완비(릴스는 views·likes·comments, 피드는 likes·comments — 02 서빙 usable 정의와 동일).
--
-- 자격 개정(07-20, PO 결정 — 스펙 docs/superpowers/specs/2026-07-20-vertex-migration-recent12-backfill-design.md):
-- "백필 MVP 제외"(07-19)를 번복, 최근 N개(app_setting 'analytics.recent-window', 01 뷰와 공유) 윈도우
-- 안이면 제때 크롤 실패(늦크롤 백필)여도 후보에 포함한다. 제때 크롤 판정을 LATERAL로 승격해 timely
-- 컬럼(제때 크롤 여부)으로 노출 — 소비자(백필 러너·일상 잡)가 V33 metric_timeliness 마킹
-- (timely/late_backfill)을 이 컬럼으로 결정한다.
--
-- 플랜 회귀 수정(07-20, 품질 리뷰 실측 반영): timely·in_window를 그냥 WHERE에 OR로 두면 옵티마이저가
-- 그 술어를 v_contents 안쪽 idx_content_influencer 인덱스 스캔의 Filter로까지 밀어넣어, 이후 pr 조인
-- 순서·방식까지 오판했다 — 실데이터(스냅샷 캐시 ~2.9만 행)에서 152ms→9.5~12초로 폭주(EXPLAIN 실측,
-- 13k행 Materialize를 21k회 재스캔). 안쪽 서브쿼리를 `OFFSET 0`으로 감싸 최적화 배리어를 세우고(캡션·
-- 성숙 가드 + timely/in_window 계산까지만 안에서 확정), OR 필터는 배리어 밖 바깥 SELECT에서 적용해
-- 술어 푸시다운을 차단한다 — 의미(자격·timely 값·컬럼 순서)는 동일, 플랜만 바뀐다.
-- (참고: timely를 출력 컬럼으로 노출해야 해서 옵티마이저가 이걸 세미조인으로 완전히 못 접는다 —
-- 그래서 배리어 이후에도 베이스라인(OR·timely 노출 이전, ~150ms)보단 느리다. MATERIALIZED CTE도
-- 동급 배리어로 동작하나 넓은 행(캡션·썸네일 포함)을 임시파일에 스풀해 이 안(OFFSET 0)보다 실측
-- 12% 가량 더 느렸다 — 원인은 CTE 강제 구체화, OFFSET 0은 스트리밍 배리어라 스풀이 없다.)
CREATE OR REPLACE VIEW analytics.v_analysis_candidates AS
SELECT
  short_code,
  content_type,
  account_handle,
  uploaded_at,
  caption,
  thumbnail_url,
  followers,
  views,
  likes,
  comments,
  metric_captured_at,
  timely,
  -- 인스타 유료 파트너십 태그 — v_contents가 이미 핀 스냅샷에서 들고 있어 그대로 통과시킨다
  -- (조인 추가 없음 = 플랜 불변). LLM 프롬프트에 확정 사실로 싣는 용도.
  ad_marked
FROM (
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
    v.metric_captured_at,
    v.ad_marked,
    t.timely,
    -- 최근 N개 윈도우(01 뷰) 포함 여부도 배리어 안에서 미리 계산해 바깥 OR과 분리한다.
    EXISTS (SELECT 1 FROM analytics.v_recent_content rw WHERE rw.short_code = v.short_code) AS in_window
  FROM analytics.v_contents v
  LEFT JOIN analytics.v_base_profile pr ON pr.username = v.account_handle
  CROSS JOIN LATERAL (
    SELECT EXISTS (
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
    ) AS timely
  ) t
  WHERE v.caption IS NOT NULL AND btrim(v.caption) <> ''
    -- 성숙: 제때창이 완전히 지난 날만 (업로드일 + pin + slack <= 오늘 KST) — 백필 경로도 동일 적용
    AND (v.posted_at AT TIME ZONE 'Asia/Seoul')::date
          + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3)
          + COALESCE((SELECT value::int FROM app_setting WHERE key = 'analytics.analyze-timely-slack-days'), 1)
        <= (now() AT TIME ZONE 'Asia/Seoul')::date
  -- 최적화 배리어: OFFSET 0은 실제로 행을 건너뛰지 않지만(0개), 플래너가 이 서브쿼리 경계를 넘어
  -- 바깥 WHERE(timely OR in_window)를 안쪽 스캔까지 밀어넣지 못하게 막는다(PG 관용구).
  -- ⚠️ 비공식 동작(언어 계약 아님) — CTE 기본 인라인화(PG12) 전례처럼 무력화될 수 있으니
  -- PG 메이저 업그레이드 시 EXPLAIN으로 배리어 유효성 재확인할 것.
  OFFSET 0
) candidates
WHERE timely
  -- 늦크롤 백필: 최근 N개 윈도우(01 뷰) 안이면 포함 — 지표는 핀(v_contents) 그대로, 마킹은 소비자가
  OR in_window;
