-- [일회성 운영 SQL] content_analyses 재조정 — 04 뷰 날짜기준 가드(feat/analysis-date-based-window) 후속.
--
-- ⚠️ 2026-07-30: content_metric_snapshots 미러가 제거됐다(소비자 부재·미러 시간 12분 30초 중 6~7분 차지).
--   이 스크립트는 MAX/COUNT가 아니라 short_code별 날짜창 안 개별 스냅샷 존재 여부를 확인하므로
--   contents.metric_captured_at(콘텐츠당 핀 값 1개)으로는 대체 불가 — 재실행하려면 아래
--   content_metric_snapshots 참조를 raw DB의 analytics.v_content_metric_snapshots(또는
--   content_snapshot_cache) 직접 조회로 고쳐야 한다. 로직 자체는 그대로 둔다(1회성 스크립트).
--
-- 배경: content_analyses는 append-only라, 가드 기준이 바뀌기 전(시간 간격·느슨한 slack) 분석된 행이 잔존한다.
--   날짜기준 가드(캡처 캘린더일 = 업로드일 + pin(3) ~ +pin+slack)로는 후보가 아닌 행을 정리한다.
--   삭제는 안전하다: 지금 자격 없는 행만 지우므로 후보 뷰가 재선택하지 않는다. 나중에 크롤이
--   D+3 당일 usable 스냅샷을 채워 재자격되면 그때 정상적으로 재분석된다(self-healing).
--
-- 실행 위치: analysis DB (운영 deploy-postgres-1 / DB `analysis`). 04 뷰 배포(raw DB) 후에 돌린다.
-- 기준값은 배포 뷰와 동일: metric-pin-days=3, analyze-timely-slack-days=1(운영 app_setting 설정값).
--   ⚠️ 운영 slack이 다르면 아래 interval '1 day'(=slack) 를 맞춰라.
-- usable 정의는 04 뷰·02 서빙과 동일: likes·comments 완비 ∧ (피드거나 릴스면 views 완비).
--
-- dry-run: 맨 끝 ROLLBACK. 실제 반영은 ROLLBACK → COMMIT 으로 바꿔 실행.

BEGIN;

CREATE TEMP TABLE stale ON COMMIT DROP AS
SELECT an.short_code
FROM content_analyses an
JOIN contents c USING (short_code)
WHERE NOT EXISTS (
  SELECT 1 FROM content_metric_snapshots s
  WHERE s.short_code = an.short_code
    -- 캡처 캘린더일(KST) ∈ [업로드일+3, 업로드일+3+slack(1)) = 업로드일+3 당일
    AND (s.captured_at AT TIME ZONE 'Asia/Seoul')::date
          >= (c.posted_at AT TIME ZONE 'Asia/Seoul')::date + 3
    AND (s.captured_at AT TIME ZONE 'Asia/Seoul')::date
          <  (c.posted_at AT TIME ZONE 'Asia/Seoul')::date + 3 + 1
    AND s.likes IS NOT NULL AND s.comments IS NOT NULL
    AND (c.content_type <> 'reels' OR s.views IS NOT NULL)
);

\echo '=== 삭제 대상 총계 (날짜기준 가드) ==='
SELECT count(*) AS to_delete FROM stale;

\echo '=== 유형별 · 게시일 커버리지 ==='
SELECT c.content_type, count(*) AS n,
       min((c.posted_at AT TIME ZONE 'Asia/Seoul')::date) AS oldest,
       max((c.posted_at AT TIME ZONE 'Asia/Seoul')::date) AS newest
FROM content_analyses an JOIN contents c USING (short_code)
WHERE an.short_code IN (SELECT short_code FROM stale)
GROUP BY 1 ORDER BY 1;

\echo '=== 삭제 전/후 content_analyses 총계 ==='
SELECT count(*) AS before_delete FROM content_analyses;
DELETE FROM content_analyses WHERE short_code IN (SELECT short_code FROM stale);
SELECT count(*) AS after_delete FROM content_analyses;

ROLLBACK;
