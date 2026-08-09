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
--   새 분류표(에스테틱 포함)로 전량 재분석한다.
--
-- ⚠️ 재분석 결과는 둘 중 하나이며 **한쪽은 되돌릴 수 없다**:
--   (a) 에스테틱이면 main_category='esthetic'으로 채워진다 — 이 스크립트의 목적.
--   (b) 에스테틱이 아니면 is_beauty=false로 **종결 저장**된다(ContentAnalysisJob.asNonBeauty —
--       07-20 재시도 루프 차단 설계). 다시 null로 돌아오지 않으므로 account_beauty_ratio(V45)의
--       beauty_count가 그만큼 줄고, was 발굴 목록의 뷰티 비율 게이트 통과 여부가 바뀔 수 있다.
--       재실행 시엔 술어(is_beauty IS TRUE)에 안 걸리므로 루프 멱등성 자체는 유지된다.
--
-- 커버리지 갭: 이미 데일리 잡을 탄 에스테틱 게시물은 위 (b) 경로로 `is_beauty=false,
--   main_category NULL` 상태라 이 스크립트의 술어에 걸리지 않는다. 그렇다고 대상을 넓히면
--   진짜 비뷰티와 구분이 불가능해 대량 오삭제가 되므로, 안전하게 좁힌 채로 둔다 —
--   대신 아래 dry-run에 갭 규모를 참고 카운트로 출력한다.
--
-- 비용: 삭제 건수만큼 LLM 콜 재발생. 실행 전 아래 dry-run 카운트로 건수 확인 후 결정할 것.
-- 재분석 전까지 해당 콘텐츠는 서빙·통계에서 일시 빠진다(데일리 잡이 순차 복구(쿼터 초과 시 수일)).
-- ⚠️ 재분석 행은 baseline 스냅샷·analyzed_at이 재분석 시점 값으로 바뀐다(원값 복원 불가).
-- 복구 시점: timely 삭제분은 데일리 잡, non-timely 삭제분은 LATE_BACKFILL_ANALYZE(21:00 UTC 별도 진입점)가 받는다 — 건수가 일일 쿼터를 넘으면 며칠에 걸친다.
--
-- 실행 위치: 운영 postgres 컨테이너 psql (crawler·analysis 두 DB를 \c로 오간다).
--   예: docker exec -it crawler-postgres-1 psql -U crawler -d crawler -v ON_ERROR_STOP=1 -f /tmp/requalify.sql
-- dry-run: 그대로 실행하면 ROLLBACK. 반영은 맨 끝 ROLLBACK을 COMMIT으로 바꿔 재실행.

-- 오류 시 즉시 중단 (-v ON_ERROR_STOP=1을 빠뜨린 실행도 방어)
\set ON_ERROR_STOP on

-- ① raw DB: 현재 분석 후보인 short_code 추출 (자격 판정은 뷰가 정본 — 제때/윈도우 조건 승계)
\c crawler
-- 이전 실행 잔여 CSV 제거 — \copy가 실패했는데 낡은 후보 목록으로 DELETE하는 경로를 차단한다.
\! rm -f /tmp/esthetic_requalify_candidates.csv
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

\echo '=== 참고: is_beauty=false 미분류 ∩ 여전히 후보 (asNonBeauty 종결분 — 이 스크립트는 건드리지 않음, 갭 규모 확인용) ==='
SELECT count(*) AS non_beauty_null_still_candidate
FROM content_analyses a
WHERE a.is_beauty IS FALSE
  AND a.main_category IS NULL
  AND EXISTS (SELECT 1 FROM _still_candidates c WHERE c.short_code = a.short_code);

DELETE FROM content_analyses a
WHERE a.is_beauty IS TRUE
  AND a.main_category IS NULL
  AND EXISTS (SELECT 1 FROM _still_candidates c WHERE c.short_code = a.short_code);

\echo '=== 삭제 후 총계 ==='
SELECT count(*) AS remaining FROM content_analyses;

ROLLBACK;  -- 반영 시 COMMIT으로 변경
