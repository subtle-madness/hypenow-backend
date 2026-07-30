-- 뷰티 판정 v4(서술 언어 기준 명확화) 재판정 초기화 — CLAUDE 판정 INFLUENCER분만 대상
-- (2026-07-30 스펙, MANUAL 판정은 보존). v3(2026-07-28 컷오버) 운영 실측으로 확인된 결함 2건 대응:
--   ①재판정 범위 누락 — reset-influencer-judgments-v3.sql이 운영 미실행돼 현재 INFLUENCER
--     7,128건 중 6,605건(92.7%)이 4분류 시절(FOREIGN_INFLUENCER 선택지 자체가 없던) 판정.
--   ②v3 프롬프트 결함 — "한국어 콘텐츠 중심"을 LLM이 "한국 관련 콘텐츠"로 오독해, 한국 화장품을
--     외국어로 리뷰하는 계정을 INFLUENCER로 오분류(post-v3 표본 33/33 일본 계정).
-- 상세: docs/superpowers/plans/2026-07-30-beauty-foreign-influencer-rejudge.md
--
-- ⚠️ 전량(약 7,127건)을 한 번에 초기화하면 재판정이 끝날 때까지 그 전부가 beauty=null이 돼
-- 서빙 게이트(수집·랭킹 대상)에서 통째로 이탈한다. 그래서 beauty.batch-limit(운영 현재값 2000)에
-- 맞춘 슬라이스 단위로 나눠 돌린다 — 오래된(pre-v3 가능성이 높은) 판정부터 소진되도록
-- beauty_judged_at 오름차순(NULL 먼저)으로 정렬해 자른다.
--
-- ⚠️ 잔여량 판정에는 반드시 "작업 개시 시각"(started) 기준을 쓴다 — 재판정 결과로 다시
-- INFLUENCER가 된 계정도 beauty_source='CLAUDE'라, 시각 조건 없는 카운트는 절대 0이 되지 않는다.
-- started는 첫 슬라이스를 돌리기 전 시각으로 고정해 전 슬라이스에서 동일한 값을 쓴다.
--
-- 실행 순서(오라클 서버, raw DB 컨테이너):
--   0) 작업 개시 시각을 고정해 기록해둔다(전 슬라이스 공통):
--      STARTED=$(date -u +'%Y-%m-%d %H:%M:%S+00'); echo "$STARTED"
--   1) 슬라이스 하나 초기화(-v slice=N으로 크기 조절, 생략 시 2000):
--      docker compose exec -T postgres-raw psql -U crawler -d crawler -v slice=2000 \
--        < deploy/scripts/reset-influencer-judgments-v4.sql
--   2) 서버 어드민(8080 /ui)에서 BEAUTY 잡 트리거 → 배치가 소진될 때까지 대기.
--   3) 완료 확인 후 남은 대상 수 조회(0이면 종료, 아니면 1)부터 반복):
--      select count(*) from influencer
--       where beauty_class = 'INFLUENCER' and beauty_source = 'CLAUDE'
--         and beauty_judged_at < '<STARTED>'::timestamptz;
--
-- slice 기본값 — psql -v slice=N 으로 넘긴 값이 있으면 그것을 쓴다(무조건 \set 하면 -v가 덮인다).
\if :{?slice}
\else
  \set slice 2000
\endif
-- started(작업 개시 시각)를 넘기면 그 이후 판정분(= 이미 v4로 재판정된 계정)은 대상에서 빠져
-- 슬라이스를 반복해도 같은 계정을 두 번 판정하지 않는다. 생략 시 무제한('infinity')으로 동작.
\if :{?started}
\else
  \set started infinity
\endif

begin;
update influencer
   set beauty          = null,
       beauty_company  = null,
       beauty_class    = null,
       beauty_source   = null,
       beauty_reason   = null,
       beauty_judged_at = null
 where id in (
       select id
         from influencer
        where beauty_class = 'INFLUENCER'
          and beauty_source = 'CLAUDE'
          and (beauty_judged_at is null or beauty_judged_at < :'started'::timestamptz)
        order by beauty_judged_at nulls first, id
        limit :slice
       );
commit;
