-- 분류 어휘의 축(카테고리 계열) 컬럼 (2026-08-31). 설계:
-- docs/superpowers/specs/2026-08-31-fnb-content-taxonomy-design.md §2·§4
--
-- 축을 content_analyses의 컬럼이 아니라 어휘 테이블에 두는 이유: 카테고리는 계속 는다
-- (다음은 홈/리빙). 축마다 컬럼·LLM 필드·마이그레이션이 하나씩 늘어나는 구조를 피하고,
-- main_category가 있으면 그 대분류의 axis가 곧 콘텐츠의 축이 되게 한다.
-- 새 카테고리 추가 = 이 테이블에 INSERT 한 번.
--
-- 테이블 이름(beauty_*)은 유지한다 — 운영 DB에 있고 소비처가 여럿이라(was 랭킹 중분류 확장·
-- V35 카테고리 믹스·발굴 matview) rename 이득이 위험을 넘지 않는다(에스테틱 추가 때와 동일 판단).
--
-- DEFAULT 'beauty'로 기존 행이 전부 백필된다 — expand only, 롤링 배포 무해.
ALTER TABLE beauty_taxonomy ADD COLUMN axis text NOT NULL DEFAULT 'beauty';
ALTER TABLE beauty_distributors ADD COLUMN axis text NOT NULL DEFAULT 'beauty';

COMMENT ON COLUMN beauty_taxonomy.axis IS
  '카테고리 축 — beauty|fnb(|home_living). content_analyses.is_beauty의 파생 근거.';
COMMENT ON COLUMN beauty_distributors.axis IS
  '카테고리 축 — 그 축의 콘텐츠에만 유효한 유통사. 뷰티 게시물의 편의점 태그는 sanitize가 드랍한다.';
