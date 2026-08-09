-- [일회성 운영 SQL] 에스테틱 소급 재자격 — 대분류 esthetic 추가(V20260809063533) 후속.
--
-- 배경: 기존 분석분 중 디바이스·시술 게시물은 is_beauty=true AND main_category IS NULL로
--   남아 있다(구 어휘엔 에스테틱이 없어 sanitize 드랍). content_analyses는 INSERT-only +
--   분석 잡 NOT EXISTS 제외라 자동 재분석은 없다 → 행을 삭제해 재자격시킨다(self-heal).
--
-- ⚠️ 선례(reprocess_uncategorized_content_analyses.sql)와 결정적 차이 — 삭제 대상이 정상 분석
--   행(브랜드·광고 신호 포함)이다. 후보 뷰(v_analysis_candidates: 제때 크롤 OR 최근 N 윈도우)에
--   되살아나지 못하는 행을 지우면 기존 분석을 통째로 잃는다. 따라서 삭제는 반드시
--   **"여전히 후보 뷰에 있는 short_code"와의 교집합**으로 한정한다 — 삭제분은 다음 데일리 잡이
--   새 분류표(에스테틱 포함)로 전량 재분석한다(에스테틱이면 채워지고 아니면 다시 null — 멱등).
--
-- 비용: 삭제 건수만큼 LLM 콜 재발생. 실행 전 아래 dry-run 카운트로 건수 확인 후 결정할 것.
-- 재분석 전까지 해당 콘텐츠는 서빙·통계에서 일시 빠진다(데일리 잡 1회 내 복구).
--
-- 실행 위치: 운영 postgres 컨테이너 psql (crawler·analysis 두 DB를 \c로 오간다).
--   예: docker exec -it crawler-postgres-1 psql -U crawler -d crawler -f /tmp/requalify.sql
-- dry-run: 그대로 실행하면 ROLLBACK. 반영은 맨 끝 ROLLBACK을 COMMIT으로 바꿔 재실행.

-- ① raw DB: 현재 분석 후보인 short_code 추출 (자격 판정은 뷰가 정본 — 제때/윈도우 조건 승계)
\c crawler
\copy (SELECT short_code FROM analytics.v_analysis_candidates) TO '/tmp/esthetic_requalify_candidates.csv'

-- ② analysis DB: 후보 교집합만 삭제 (temp 테이블은 \c 이후 같은 세션에서 생성해야 유지된다)
\c analysis
CREATE TEMP TABLE _still_candidates (short_code text PRIMARY KEY);
\copy _still_candidates FROM '/tmp/esthetic_requalify_candidates.csv'

BEGIN;

\echo '=== 삭제 대상 (미분류 뷰티 ∩ 여전히 후보) ==='
SELECT count(*) AS to_delete
FROM content_analyses a
WHERE a.is_beauty IS TRUE
  AND a.main_category IS NULL
  AND EXISTS (SELECT 1 FROM _still_candidates c WHERE c.short_code = a.short_code);

\echo '=== 참고: 후보 밖이라 보존되는 미분류 뷰티 (삭제 안 함 — 데이터 유실 방지) ==='
SELECT count(*) AS kept_out_of_window
FROM content_analyses a
WHERE a.is_beauty IS TRUE
  AND a.main_category IS NULL
  AND NOT EXISTS (SELECT 1 FROM _still_candidates c WHERE c.short_code = a.short_code);

DELETE FROM content_analyses a
WHERE a.is_beauty IS TRUE
  AND a.main_category IS NULL
  AND EXISTS (SELECT 1 FROM _still_candidates c WHERE c.short_code = a.short_code);

\echo '=== 삭제 후 총계 ==='
SELECT count(*) AS remaining FROM content_analyses;

ROLLBACK;  -- 반영 시 COMMIT으로 변경
