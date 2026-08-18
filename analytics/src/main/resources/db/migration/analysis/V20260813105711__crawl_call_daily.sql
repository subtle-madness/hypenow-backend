-- 크롤러 파이프라인 유료 요청 일별 집계 미러 테이블 (설계 2026-08-13) — expand 단계 신규 테이블.
-- 소스는 raw DB의 analytics.v_crawl_call_daily, 적재는 analytics 미러(TRUNCATE+INSERT 한 트랜잭션).
-- was 어드민 전역 크롤링 비용 API가 읽는 유일한 크롤러 표면이다(was는 raw DB에 접근하지 않는다).
--
-- 컬럼 이름·순서는 CrawlCallDaily record와 일치해야 한다(§4-3) — FlywaySchemaTest가 대조한다.
-- called_on은 KST 달력일(뷰가 AT TIME ZONE 'Asia/Seoul'로 변환).
CREATE TABLE crawl_call_daily (
    job       text   NOT NULL,   -- crawler JobName 이름 그대로
    called_on date   NOT NULL,   -- KST 달력일
    calls     bigint NOT NULL,   -- 구매한 요청 수(수집 건수 아님)
    PRIMARY KEY (job, called_on)
);
