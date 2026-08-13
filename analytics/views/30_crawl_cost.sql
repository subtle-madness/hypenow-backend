-- 크롤러 파이프라인 유료 요청 일별 집계 (설계 2026-08-13 §3-2·3-3) — analysis DB
-- crawl_call_daily로 미러돼 was 어드민 전역 크롤링 비용 API가 읽는다.
--
-- 모수: request_count > 0인 실행만. Apify 실행(NULL — 결과 건당 과금)과 무료 소스(0)가
-- 이 한 조건으로 자연히 빠진다. 액터 라벨 화이트리스트를 두지 않는 이유가 이것 — 새 무료
-- 경로가 생겨도 규칙을 고칠 필요가 없다.
--
-- status는 거르지 않는다: request_count는 finishOk 경로에서만 기록되고, 요청이 나간 뒤
-- 404로 끝난 실행(ReelsJob·SimilarJob의 '콘텐츠 없음')도 요청은 이미 샀으므로 값을 유지한다.
--
-- 날짜는 KST 달력일 — monitoring의 brand_call_count.called_on·target_call_count.called_on과
-- 같은 시간대라 was가 세 소스를 한 경계로 합산한다. 자정을 넘긴 실행은 시작일 몫이다(실행
-- 단위라 요청을 시각별로 쪼갤 수 없고, 크롤 잡은 01:00~03:55 KST의 짧은 실행들이라 무의미).
--
-- 컬럼 계약: CrawlCallDaily record(job, calledOn, calls) ↔ crawl_call_daily DDL과 이름·순서
-- 일치 필수(§4-3, MirrorJob이 실행 시 대조). sum()은 numeric이라 ::bigint 캐스트 필수.
CREATE OR REPLACE VIEW analytics.v_crawl_call_daily AS
SELECT
  job,
  (started_at AT TIME ZONE 'Asia/Seoul')::date AS called_on,
  sum(request_count)::bigint AS calls
FROM analytics.v_base_crawl_run
WHERE request_count > 0
GROUP BY job, (started_at AT TIME ZONE 'Asia/Seoul')::date;
