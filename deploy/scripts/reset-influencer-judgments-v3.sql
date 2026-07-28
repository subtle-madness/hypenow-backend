-- 뷰티 판정 v3(한국어 콘텐츠 필터) 전환 — CLAUDE 판정 INFLUENCER분만 판정 초기화
-- (2026-07-28 스펙의 일회성 운영 작업, MANUAL 판정은 보존).
-- 초기화 후 서버 어드민에서 BEAUTY 잡을 트리거하면 새 5분류 기준으로 재판정된다
-- (배치 한도는 어드민 설정 beauty.batch-limit — 대상 수에 따라 수 회 실행).
--
-- 실행(오라클 서버, raw DB 컨테이너):
--   docker compose exec -T postgres-raw psql -U crawler -d crawler < deploy/scripts/reset-influencer-judgments-v3.sql
begin;
update influencer
   set beauty          = null,
       beauty_company  = null,
       beauty_class    = null,
       beauty_source   = null,
       beauty_reason   = null,
       beauty_judged_at = null
 where beauty_class = 'INFLUENCER'
   and beauty_source = 'CLAUDE';
commit;
