-- 발굴 리포트 v2 ads.brands "같은 브랜드 협업 계정"(was V2InfluencerReportRepository.findBrandCollabs) 조회용.
-- 09-03 운영 실측: 브랜드 21개 계정(ohraemakeup_)에서 브랜드마다 content_analyses(309MB) 전체
-- 순차 스캔 + jsonb 전개가 반복돼(상관 서브쿼리 SubPlan) 7.3초, 동시 2건이면 13.7초.
-- 협찬 행의 브랜드명 배열에 GIN 식 인덱스를 걸어 "내 브랜드 중 하나라도 포함한 협찬 게시물"만
-- 짚는다(?| 연산자). 로컬 스냅샷(협찬 6.5만 행) 실측: 브랜드 49개 계정 3,168ms → 8ms, 결과 전량 동일.
-- 식은 was 쿼리의 술어와 글자 단위로 같아야 인덱스가 붙는다 — 한쪽을 바꾸면 다른 쪽도.
-- content_analyses는 INSERT 전용(ContentAnalysisWriter, TRUNCATE·재생성 없음)이라 인덱스가 유지된다.
CREATE INDEX idx_content_analyses_brand_names_gin
    ON content_analyses USING gin ((jsonb_path_query_array(detected_brands, '$[*].name')))
    WHERE ad_type = 'sponsored';
