-- 크롤러 파이프라인 유료 요청 집계. 검증 축 4개:
--   ① KST 달력일 경계(UTC 15:00 = KST 다음날 00:00) ② request_count NULL·0 제외
--   ③ status 미필터(요청이 나간 실패 실행도 과금이므로 계상) ④ 잡별 그룹핑
-- 공용 dummy.sql의 crawl_run 99990000은 request_count NULL이라 자동으로 제외 모수가 된다.
-- 하니스는 실데이터 컨테이너에서 돌아 crawl_run에 실제 실행 이력이 이미 들어 있다(CI만 프레시).
-- 그래서 "없어야 한다"·"몇 행이어야 한다" 류 단언은 시드 날짜(KST 06-05·06-06)로 모수를 좁힌다.
INSERT INTO crawl_run(id, job, trigger_type, actor_id, status, request_count, started_at) VALUES
 -- 같은 KST 날(06-05)로 접히는 두 실행: 14:59:59Z = 23:59:59 KST.
 (99990010,'COLLECT','SCHEDULED','profile-hiker-mobile','SUCCEEDED', 3, timestamptz '2026-06-05 00:30:00+00'),
 (99990011,'COLLECT','SCHEDULED','profile-hiker-mobile','SUCCEEDED', 2, timestamptz '2026-06-05 14:59:59+00'),
 -- 1초 뒤 = KST 06-06 00:00:01 → 다음 날로 갈라져야 한다.
 (99990012,'COLLECT','SCHEDULED','profile-hiker-mobile','SUCCEEDED', 7, timestamptz '2026-06-05 15:00:01+00'),
 -- 잡이 다르면 다른 행.
 (99990013,'REELS','SCHEDULED','hiker-v2-clips','SUCCEEDED', 1, timestamptz '2026-06-05 00:30:00+00'),
 -- 요청은 나갔으나 404로 끝난 실행 — 과금 실체와 맞게 계상한다(status 미필터).
 (99990014,'REELS','SCHEDULED','hiker-v2-clips','FAILED', 4, timestamptz '2026-06-05 00:40:00+00'),
 -- Apify 실행(결과 건당 과금) — request_count NULL이라 제외.
 (99990015,'DISCOVER','MANUAL','apify/instagram-hashtag-scraper','SUCCEEDED', NULL, timestamptz '2026-06-05 00:30:00+00'),
 -- 무료 소스(self) — 0건이라 제외(0짜리 행을 만들지 않는다).
 (99990016,'QUALIFY','SCHEDULED','profile-self','SUCCEEDED', 0, timestamptz '2026-06-05 00:30:00+00');

DO $$
BEGIN
  -- ① COLLECT는 KST 06-05에 3+2=5, 06-06에 7로 갈린다.
  ASSERT (SELECT calls FROM analytics.v_crawl_call_daily
          WHERE job = 'COLLECT' AND called_on = date '2026-06-05') = 5,
         'COLLECT 06-05 != 5 (KST 경계 접힘)';
  ASSERT (SELECT calls FROM analytics.v_crawl_call_daily
          WHERE job = 'COLLECT' AND called_on = date '2026-06-06') = 7,
         'COLLECT 06-06 != 7 (KST 자정 넘김)';
  -- ③ 실패 실행 포함: REELS 06-05 = 1 + 4.
  ASSERT (SELECT calls FROM analytics.v_crawl_call_daily
          WHERE job = 'REELS' AND called_on = date '2026-06-05') = 5,
         'REELS 06-05 != 5 (실패 실행 미계상)';
  -- ② NULL·0은 행 자체가 없어야 한다.
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_crawl_call_daily
                     WHERE job = 'DISCOVER' AND called_on IN (date '2026-06-05', date '2026-06-06')),
         'DISCOVER 행 존재 (request_count NULL이 제외되지 않음)';
  ASSERT NOT EXISTS (SELECT 1 FROM analytics.v_crawl_call_daily
                     WHERE job = 'QUALIFY' AND called_on IN (date '2026-06-05', date '2026-06-06')),
         'QUALIFY 행 존재 (request_count 0이 제외되지 않음)';
  -- ④ 시드 날짜에서 나오는 행은 위 3개뿐.
  ASSERT (SELECT count(*) FROM analytics.v_crawl_call_daily
          WHERE called_on IN (date '2026-06-05', date '2026-06-06')) = 3,
         'v_crawl_call_daily 시드일 행 수 != 3';
END $$;
