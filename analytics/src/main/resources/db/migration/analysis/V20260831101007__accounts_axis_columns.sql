-- 계정 축 컬럼 (2026-08-31 F&B 서빙 개방). 설계:
-- docs/superpowers/specs/2026-08-31-fnb-serving-open-design.md §3
--
-- 발굴 목록의 모수가 account_summaries ⋈ accounts라, F&B 계정이 미러에 들어가는 순간
-- 무필터 목록에 섞일 경로가 생긴다. 축 컬럼으로 was가 무필터=뷰티를 명시할 수 있게 한다.
-- nullable인 이유: 롤링 창에서 구 analytics 미러(구 record)는 이 컬럼을 채우지 않는다 —
-- was는 COALESCE(beauty, true)로 읽는다(기존 행은 전부 뷰티 모수 출신이라 true 간주가 정확).
-- expand only, 롤링 배포 무해.
ALTER TABLE accounts ADD COLUMN beauty boolean;
ALTER TABLE accounts ADD COLUMN fnb boolean;

COMMENT ON COLUMN accounts.beauty IS
  '뷰티 인플루언서 축(raw influencer.beauty∧¬beauty_company 미러) — 무필터 발굴의 기본 모수.';
COMMENT ON COLUMN accounts.fnb IS
  'F&B 인플루언서 축(raw influencer.fnb∧¬fnb_company 미러) — F&B 필터 발굴의 모수.';
