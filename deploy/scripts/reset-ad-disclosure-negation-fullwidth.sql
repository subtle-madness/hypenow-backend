-- 광고 표기 판정 오탐 2건 수정(2026-08-28) 재판정 초기화 —
-- feat/monitoring-ad-negation-scope 브랜치, AdDisclosurePatterns 스팬 겹침 축소 + 전각 해시 매칭
-- 대응. 이 SQL은 파일만 준비하는 것 — 배포 전에는 절대 실행하지 않는다.
--
-- 대상은 이번 수정으로 판정이 뒤집힐 수 있는 행만이다(수정 배포 전 판정된 것만 — 배포 후 새로
-- 들어온 판정은 이미 새 로직을 탔으므로 대상이 아니다):
--   ① NEGATION 가드를 "캡션 어디든 있으면 전체 포기"에서 "매칭 후보와 스팬이 겹칠 때만 그 후보
--      제외"로 좁혔다 — "#광고 …(무관한 문장)… 내돈내산"류가 종전엔 Tier1을 통째로 포기해
--      NOT_DISCLOSED/INSUFFICIENT/UNCERTAIN으로 오귀속됐다(_arinzip Db7xIiTiSPy,
--      _bbohouse DbPMV2Jmd05 운영 실측). 이 축소는 **부정어와 Tier1 사전 문구가 같은 캡션에
--      공존할 때만** 판정을 바꿀 수 있다 — 부정어만 있고 사전 문구가 아예 없는 캡션은 구·신
--      로직 모두 Tier1이 매칭 후보를 못 찾아 LLM(Tier2)으로 넘어가므로 결과가 무변화다.
--   ② 해시태그 사전 5종(#유료광고·#광고·#협찬·#제품제공·#상품제공)과 AdPositionRule.FIRST_HASHTAG가
--      전각 해시(＃, U+FF03)를 인식하지 못해 놓쳤다(dodami_0607 DYs1rKgEgRv "＃협찬 | #아워팜" 실측).
--   ①-보강 괄호형 "(광고)"·"[광고]"·"(협찬)"·"[협찬]"을 Tier1 고신뢰 사전에 새로 등재했다
--      (_bbohouse "(광고) 지만 내돈내산" 실측) — 부정어 유무와 무관하게 구 로직은 아예 못 잡던
--      패턴이라 캡션에 이 패턴만 있어도(부정어 공존 여부 상관없이) 대상이다.
--
-- DISCLOSED 행은 건드리지 않는다 — 이번 수정은 표기 인식을 "추가"하는 방향뿐이라(새 매칭이
-- 늘거나 그대로거나) DISCLOSED가 재판정으로 나빠질 수 없다.
--
-- ⚠️ WHERE는 "부정어 존재" 단독이 아니라 "부정어 + 사전 문구 공존"으로 좁혀야 한다 — 08-28
-- 운영 실측: 부정어 단독 조건(`caption ~ '내돈내산|...'`)은 731건을 잡지만, 그중 실제로 판정이
-- 바뀔 수 있는 행(사전 문구가 캡션에 실존하는 행)은 41건뿐이었다. 나머지 690건은 부정어만 있고
-- Tier1 사전 문구가 없어 재판정해도 그대로 LLM행 → 같은 결과가 나오는 LLM 콜 낭비였다. 아래
-- WHERE는 이 41건 기준으로 좁힌 버전이다.
--
-- 실행 시점: 이 수정이 담긴 배포가 완료된 **이후**에만 실행한다(배포 전 실행하면 구 로직으로
-- 다시 판정돼 헛수고).
--
-- 실행 순서(오라클 서버, monitoring 컨테이너 — brand_post_meta는 app 스키마가 아니라 monitoring
-- 모듈의 monitoring DB다. 운영: 컨테이너 deploy-postgres-1, DB monitoring, 유저 monitoring):
--   0) 대상 건수 확인(실행 전 반드시 먼저 SELECT로 확인 — 08-28 운영 실측 41건):
--        select count(*) from brand_post_meta
--         where ad_verdict in ('NOT_DISCLOSED', 'INSUFFICIENT', 'UNCERTAIN')
--           and (
--                 ( caption ~ '내돈내산|(광고|협찬)\s*(이|가|은|는)?\s*(아니|아님)'
--                   and caption ~ '[#＃](유료광고|광고|협찬|제품제공|상품제공)|제품을?\s*제공\s*받(아|았|은|고)|광고입니다|유료\s*광고|대가성\s*광고|협찬\s*받(았|은)|제공받아\s*작성|소정의\s*(수수료|원고료|광고료)|[\(\[]\s*(광고|협찬)\s*[\)\]]' )
--              or caption ~ '＃(유료광고|광고|협찬|제품제공|상품제공)'
--              or caption ~ '[\(\[]\s*(광고|협찬)\s*[\)\]]'
--           );
--   1) 위 건수가 41건 안팎임을 확인한 뒤 본 스크립트 실행:
--        docker exec -i deploy-postgres-1 psql -U monitoring -d monitoring \
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
        -- ① 부정어 + 사전 문구 공존(가드 축소로 판정이 실제로 바뀔 수 있는 행만)
        ( caption ~ '내돈내산|(광고|협찬)\s*(이|가|은|는)?\s*(아니|아님)'
          and caption ~ '[#＃](유료광고|광고|협찬|제품제공|상품제공)|제품을?\s*제공\s*받(아|았|은|고)|광고입니다|유료\s*광고|대가성\s*광고|협찬\s*받(았|은)|제공받아\s*작성|소정의\s*(수수료|원고료|광고료)|[\(\[]\s*(광고|협찬)\s*[\)\]]' )
        -- ② 전각 해시 사전 문구(신규 매칭 — 구 로직은 반각 #만 인식)
     or caption ~ '＃(유료광고|광고|협찬|제품제공|상품제공)'
        -- ①-보강 괄호형(신규 등재 — 부정어 공존 여부와 무관하게 구 로직이 아예 못 잡던 패턴)
     or caption ~ '[\(\[]\s*(광고|협찬)\s*[\)\]]'
   );

commit;
