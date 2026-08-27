-- 광고 표기 판정에 FOREIGN_POST verdict 추가(2026-08-27 결정, DECISIONS.md 참조) — 캡션 전체가
-- 비한국어인 게시물은 한국 공정위 지침 적용 대상이 아니므로 판정 제외로 확정한다(신규 Tier0 메타
-- 규칙, AdDisclosureJudgeService.judgeCore). CHECK 값 추가는 허용 범위를 넓히기만 하므로 롤링 중
-- 구버전 코드가 이 값을 아직 안 써도 위반이 나지 않는다(순수 additive, archived_rows_archived_reason_check
-- 선례와 동일 패턴 — V20260811090000__archive_reason_notice_deleted.sql).
ALTER TABLE brand_post_meta DROP CONSTRAINT brand_post_meta_ad_verdict_check;
ALTER TABLE brand_post_meta ADD CONSTRAINT brand_post_meta_ad_verdict_check
    CHECK (ad_verdict IN
        ('DISCLOSED', 'NOT_DISCLOSED', 'INSUFFICIENT', 'UNCERTAIN', 'FOREIGN_POST'));
