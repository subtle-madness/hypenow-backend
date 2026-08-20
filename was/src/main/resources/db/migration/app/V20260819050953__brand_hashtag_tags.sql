-- 사용자 스코프 해시태그 태그 원장(2026-08-19 설계 — 상호작용 사용자 스코프 개정) — expand 단계.
--
-- monitoring DB의 brand_hashtag(브랜드 단위, PK (brand_id, tag))는 스윕 대상 태그를 브랜드
-- 단위로만 기억해 "누가 그 태그를 추가했는지"를 모른다 — 한 유저가 태그를 지우면 같은 브랜드에
-- 연결된 다른 유저가 의존하는 태그까지 스윕 대상에서 빠지는 문제가 있었다. 이 테이블이 "이 유저가
-- 이 브랜드에 등록한 태그"를 was 쪽에 별도로 기억해, monitoring에는 여전히 전체 연결 유저 태그의
-- 합집합만 반영한다(감지 데이터 자체는 브랜드 공유 유지 — 정책 변경 없음). 삭제는 다른 유저가 그
-- 태그를 갖고 있지 않을 때만 monitoring에 반영한다(BrandDirectPostRepository.hasOtherRegistrant와
-- 같은 패턴).
--
-- monitoring이 물리적으로 다른 DB라 SQL 조인 불가 — 조합은 was 코드에서(V1BrandAccountService).
CREATE TABLE app.brand_hashtag_tags (
    user_id    bigint      NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
    brand_id   bigint      NOT NULL,   -- monitoring brand_account.id 논리 참조(크로스 DB FK 금지)
    tag        text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, brand_id, tag)
);

-- 브랜드별 합집합 조회(monitoring 동기화)·existsForBrand(최초 시딩 판정)·hasOtherUserWithTag(삭제
-- 시맨틱) 공용 인덱스 — 전부 brand_id(+tag) 스코프 조회다.
CREATE INDEX brand_hashtag_tags_brand_idx ON app.brand_hashtag_tags (brand_id, tag);
