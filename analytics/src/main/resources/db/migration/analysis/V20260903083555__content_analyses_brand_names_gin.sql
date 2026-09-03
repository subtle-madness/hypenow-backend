-- 발굴 리포트 v2 ads.brands "같은 브랜드 협업 계정"(was V2InfluencerReportRepository.findBrandCollabs) 조회용.
-- 09-03 운영 실측: 브랜드 21개 계정(ohraemakeup_)에서 브랜드마다 content_analyses(309MB) 전체
-- 순차 스캔 + jsonb 전개가 반복돼(상관 서브쿼리 SubPlan) 7.3초, 동시 2건이면 13.7초.
-- 협찬 행의 브랜드명 배열에 GIN 식 인덱스를 걸어 "내 브랜드 중 하나라도 포함한 협찬 게시물"만
-- 짚는다(?| 연산자). 로컬 스냅샷(협찬 6.5만 행) 실측: 브랜드 49개 계정 3,168ms → 8ms, 결과 전량 동일.
-- 식은 was 쿼리의 술어와 글자 단위로 같아야 인덱스가 붙는다 — 한쪽을 바꾸면 다른 쪽도.
-- content_analyses는 INSERT 전용(ContentAnalysisWriter, TRUNCATE·재생성 없음)이라 인덱스가 유지된다.
-- fastupdate = off: 켜두면 조회가 트리 탐색에 더해 펜딩 리스트(최대 4MB, VACUUM 전까지 누적)를 선형으로
-- 훑어 읽기 지연이 적재 누적량에 흔들린다 — 이 인덱스의 목적이 ai-report 읽기 지연이라 읽기를 결정론적으로
-- 둔다. 쓰기 비용은 행당 브랜드 평균 1.08개라 직접 삽입해도 무시 수준(09-03 스냅샷 5,000행 INSERT 벤치
-- 인덱스 유무 차이 없음, 08-31 배치 적재 최대 시간당 5.9만 행에도 초 단위 미만).
CREATE INDEX idx_content_analyses_brand_names_gin
    ON content_analyses USING gin ((jsonb_path_query_array(detected_brands, '$[*].name')))
    WITH (fastupdate = off)
    WHERE ad_type = 'sponsored';
