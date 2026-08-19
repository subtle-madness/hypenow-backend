-- 해시태그 발견 게시물의 매칭 태그 전체 기록(2026-08-19 설계, was 사용자 스코프 필터 지원) — expand 단계.
--
-- brand_hashtag_post.matched_tag(단일, "발견 경로 태그(운영 디버그용)"으로 주석돼 있는 컬럼)는 그
-- 게시물을 처음 저장한 태그 하나만 기억한다 — 같은 게시물이 여러 활성 태그의 recent 열거에도
-- 걸려도(브랜드 관련 게시물은 여러 태그를 동시에 다는 경우가 흔하다) 두 번째부터는
-- existingCodes dedup에 걸려 조용히 버려졌다. was가 "조회자 본인이 관리하는 태그에 매칭된
-- 게시물만" 필터링하려면 그 전체 집합이 필요하다.
--
-- 별도 M:N 테이블로 둔 이유(jsonb 배열 대신) — 프로젝트 컨벤션(text[] 대신 jsonb)은 한 행에 속한
-- 단일 속성값(RawComment.payload류)을 겨냥한 규칙이다. 이건 그 모양이 아니라 진짜 다대다 관계고,
-- 스윕이 돌 때마다(태그가 나중에 추가돼도) 점진적으로 원소가 늘어난다 — jsonb 배열이면 매번
-- read-modify-write로 중복 제거까지 손수 해야 하는데, 테이블이면 ON CONFLICT DO NOTHING으로
-- 끝난다. brand_post_campaigns(게시물↔캠페인 N:M)도 이 저장소에서 이미 테이블로 푼 같은 모양의
-- 문제다.
--
-- matched_tag 컬럼 자체는 건드리지 않는다(expand-contract, "운영 디버그용" 원 용도 유지).
CREATE TABLE brand_hashtag_post_matched_tags (
    brand_id   bigint      NOT NULL,
    short_code text        NOT NULL,
    tag        text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (brand_id, short_code, tag),
    FOREIGN KEY (brand_id, short_code) REFERENCES brand_hashtag_post (brand_id, short_code) ON DELETE CASCADE
);

-- 백필 — matched_tag는 NOT NULL이라(항상 값이 있다) 기존 행 전부를 1원소 집합으로 안전하게
-- 옮길 수 있다. 커버 안 되는 행(이 백필 이후에도 매칭 태그가 비어 있는 경우)은 이 마이그레이션
-- 시점엔 존재하지 않지만, was 쪽 필터는 그래도 "매칭 기록이 아예 없으면 전원 노출(fail-open)"로
-- 방어한다(회귀 방지 — 표시 여부가 새 테이블의 완결성에 발목 잡히면 안 된다).
INSERT INTO brand_hashtag_post_matched_tags (brand_id, short_code, tag)
SELECT brand_id, short_code, matched_tag FROM brand_hashtag_post
ON CONFLICT DO NOTHING;

CREATE INDEX brand_hashtag_post_matched_tags_tag_idx ON brand_hashtag_post_matched_tags (brand_id, tag);
