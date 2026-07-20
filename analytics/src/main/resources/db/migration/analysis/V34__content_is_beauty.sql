-- 콘텐츠 단위 뷰티 여부 (07-20). 통합 1콜 속성 분석의 isBeauty를 저장.
--   true  : 뷰티 콘텐츠 (랭킹·recentContents 노출 대상)
--   false : 비뷰티(뷰티 인플루언서의 일상글 등) — 서빙 제외, NOT EXISTS로 재분석 루프 이탈
--   null  : 미판정 (V34 이전 행 중 main_category NULL 실패분 — ops 재분석 대상)
-- 기존 행 백필: main_category가 있으면 뷰티가 확정이므로 true. NULL 카테고리 행은 손대지 않는다
--   (ops/reprocess_uncategorized_content_analyses.sql이 삭제→재분석으로 채운다).
ALTER TABLE content_analyses ADD COLUMN is_beauty boolean;

UPDATE content_analyses SET is_beauty = true WHERE main_category IS NOT NULL;
