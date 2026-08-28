-- 광고 표기 판정 오탐 2건 수정(2026-08-28) 재판정 초기화 —
-- feat/monitoring-ad-negation-scope 브랜치, AdDisclosurePatterns 스팬 겹침 축소 + 전각 해시 매칭
-- 대응. 이 SQL은 파일만 준비하는 것 — 배포 전에는 절대 실행하지 않는다.
--
-- 대상은 이번 수정으로 판정이 뒤집힐 수 있는 행만이다(수정 배포 전 판정된 것만 — 배포 후 새로
-- 들어온 판정은 이미 새 로직을 탔으므로 대상이 아니다):
--   ① NEGATION 가드를 "캡션 어디든 있으면 전체 포기"에서 "매칭 후보와 스팬이 겹칠 때만 그 후보
--      제외"로 좁혔다 — "#광고 …(무관한 문장)… 내돈내산"류가 종전엔 Tier1을 통째로 포기해
--      NOT_DISCLOSED/INSUFFICIENT/UNCERTAIN으로 오귀속됐다(_arinzip Db7xIiTiSPy,
--      _bbohouse DbPMV2Jmd05 운영 실측).
--   ② 해시태그 사전 5종(#유료광고·#광고·#협찬·#제품제공·#상품제공)과 AdPositionRule.FIRST_HASHTAG가
--      전각 해시(＃, U+FF03)를 인식하지 못해 놓쳤다(dodami_0607 DYs1rKgEgRv "＃협찬 | #아워팜" 실측).
--   ①-보강 괄호형 "(광고)"·"[광고]"·"(협찬)"·"[협찬]"을 Tier1 고신뢰 사전에 새로 등재했다
--      (_bbohouse "(광고) 지만 내돈내산" 실측).
--
-- DISCLOSED 행은 건드리지 않는다 — 이번 수정은 표기 인식을 "추가"하는 방향뿐이라(새 매칭이
-- 늘거나 그대로거나) DISCLOSED가 재판정으로 나빠질 수 없다. 재판정 낭비를 줄이기 위해
-- NOT_DISCLOSED·INSUFFICIENT·UNCERTAIN 중 위 세 정규식 중 하나라도 캡션에 걸리는 행만 추린다.
--
-- 실행 시점: 이 수정이 담긴 배포가 완료된 **이후**에만 실행한다(배포 전 실행하면 구 로직으로
-- 다시 판정돼 헛수고).
--
-- 실행 순서(오라클 서버, raw DB 컨테이너 — 이 판정은 app 스키마이므로 was가 붙는 DB):
--   0) 대상 건수 확인(실행 전 반드시 먼저 SELECT로 확인):
--        select count(*) from brand_post_meta
--         where ad_verdict in ('NOT_DISCLOSED', 'INSUFFICIENT', 'UNCERTAIN')
--           and (
--                 caption ~ '내돈내산|(광고|협찬)\s*(이|가|은|는)?\s*(아니|아님)'
--              or caption ~ '＃'
--              or caption ~ '[\(\[]\s*(광고|협찬)\s*[\)\]]'
--           );
--   1) 위 건수가 합리적 범위(수백~수천 단위)임을 확인한 뒤 본 스크립트 실행:
--        docker compose exec -T postgres psql -U <user> -d <app db> \
--          < deploy/scripts/reset-ad-disclosure-negation-fullwidth.sql
--   2) 백필 잡(브랜드 모니터링 광고 표기 재판정 배치)이 ad_verdict null 행을 다시 집어 재판정한다.
--      재판정 완료까지 해당 행은 판정 대기 상태로 노출(표기 상태 미확정)된다는 점 인지하고 실행.

begin;

update brand_post_meta
   set ad_verdict          = null,
       ad_verdict_source   = null,
       ad_violations       = null,
       ad_evidence         = null,
       ad_judged_at        = null,
       judged_caption_hash = null
 where ad_verdict in ('NOT_DISCLOSED', 'INSUFFICIENT', 'UNCERTAIN')
   and (
        caption ~ '내돈내산|(광고|협찬)\s*(이|가|은|는)?\s*(아니|아님)'
     or caption ~ '＃'
     or caption ~ '[\(\[]\s*(광고|협찬)\s*[\)\]]'
   );

commit;
