-- [일회성 운영 SQL] content_analyses 재조정 — 04 뷰 EXISTS 가드 재설계(feat/analysis-candidate-exists) 후속.
--
-- 배경: content_analyses는 append-only라, 가드 기준이 바뀌기 전(핀 기반·느슨한 하한) 분석된 행이 잔존한다.
--   새 결정론 가드(창 [posted+3, posted+5)에 usable 스냅샷 EXISTS)로는 후보가 아닌 행을 정리한다.
--   삭제는 안전하다: 지금 자격 없는 행만 지우므로 후보 뷰가 재선택하지 않는다. 나중에 크롤이
--   창 안 usable 스냅샷을 채워 재자격되면 그때 정상적으로 재분석된다(self-healing).
--
-- 실행 위치: analysis DB (운영 deploy-postgres-1 / DB `analysis`). 04 뷰 배포(raw DB) 후에 돌린다.
-- 기준값은 배포 뷰와 동일: metric-pin-days=3, analyze-timely-slack-days=2 (app_setting 오버라이드 없음).
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
    AND s.captured_at >= c.posted_at + interval '3 day'
    AND s.captured_at <  c.posted_at + interval '5 day'
    AND s.likes IS NOT NULL AND s.comments IS NOT NULL
    AND (c.content_type <> 'reels' OR s.views IS NOT NULL)
);

\echo '=== 삭제 대상 총계 (새 EXISTS 가드 기준) ==='
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
