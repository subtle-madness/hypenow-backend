-- 뷰티 판정 v2 전환 — 판정분 전체 초기화(MANUAL 포함, 2026-07-20 스펙 §4-5의 일회성 운영 작업).
-- 초기화 후 서버 어드민에서 BEAUTY 잡을 트리거하면 새 4분류 기준으로 재판정된다
-- (배치 한도는 어드민 설정 beauty.batch-limit로 조절).
--
-- 실행(오라클 서버, raw DB 컨테이너):
--   docker compose exec -T postgres-raw psql -U crawler -d crawler < deploy/scripts/reset-beauty-judgments.sql
begin;
update influencer
   set beauty          = null,
       beauty_company  = null,
       beauty_class    = null,
       beauty_source   = null,
       beauty_reason   = null,
       beauty_judged_at = null
 where beauty is not null or beauty_source is not null;
commit;
