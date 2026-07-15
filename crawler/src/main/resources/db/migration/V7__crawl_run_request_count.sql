-- 비(非)Apify 소스(HikerAPI·자체크롤)의 과금/요청 수 추적.
-- HikerAPI 발굴: 구매한 페이지 수($0.001×N). Apify 실행은 NULL(액터 과금은 apify_run_id로 추적).
ALTER TABLE crawl_run ADD COLUMN request_count integer;
