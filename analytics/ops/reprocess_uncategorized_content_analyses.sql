-- [일회성 운영 SQL] main_category 미분류 실패분 재분석 — 콘텐츠 뷰티 판별(V34) 후속.
--
-- 배경: V34 이전 분석분 중 main_category NULL 342건은 두 원인이 섞여 있다.
--   (a) 비뷰티 콘텐츠 — 새 파이프라인은 is_beauty=false로 저장해 서빙 제외.
--   (b) sanitize 드랍 — 새 파이프라인은 서브라벨→대분류 역유도로 복구.
--   둘 다 "행이 있으면 NOT EXISTS로 영영 재분석 제외"라 지금은 못 고친다 → 삭제해 재자격시킨다(self-heal).
--
-- 대상: is_beauty IS NULL AND main_category IS NULL (= V34 백필이 손대지 않은 pre-V34 실패분).
--   ⚠️ is_beauty=false(재분석으로 생긴 정상 비뷰티 행)는 건드리지 않는다 → 재실행 멱등.
-- 삭제는 안전: 지금 자격 없는 행만 지우므로 후보 뷰가 즉시 되살리지 않는다. 여전히 후보인 것만
--   데일리 잡이 새 프롬프트·스키마(isBeauty·역유도)로 재분석한다. 더 이상 후보 아닌 것(늦크롤 등)은
--   삭제만 되고 유입 없음 — 어차피 실패/비뷰티라 손실 아님.
--
-- 실행 위치: analysis DB (운영 postgres / DB `analysis`). V34 적용 후에 돌린다.
-- 무료 쿼터(일 ~1,500콜) 내 소화 가능. dry-run은 맨 끝 ROLLBACK, 반영은 COMMIT으로 바꿔 실행.

BEGIN;

\echo '=== 삭제 대상 총계 (pre-V34 미분류 실패분) ==='
SELECT count(*) AS to_delete
FROM content_analyses
WHERE is_beauty IS NULL AND main_category IS NULL;

\echo '=== 삭제 전/후 content_analyses 총계 ==='
SELECT count(*) AS before_delete FROM content_analyses;
DELETE FROM content_analyses WHERE is_beauty IS NULL AND main_category IS NULL;
SELECT count(*) AS after_delete FROM content_analyses;

ROLLBACK;
