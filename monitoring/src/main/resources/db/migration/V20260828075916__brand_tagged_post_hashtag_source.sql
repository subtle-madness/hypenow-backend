-- 해시태그 직접 수집 전환(2026-08-27 설계 §1) — expand 단계, nullable ADD + 신규 테이블만.
--
-- 접근 A: 해시태그 발견 게시물을 별도 테이블이 아니라 brand_tagged_post 풀에 흡수한다.
-- tag_detected_at / direct_registered_at / hashtag_detected_at 세 개의 nullable 타임스탬프 조합이
-- 한 행으로 세 성분과 그 겹침을 표현한다(V20260818040742 direct 합류와 동형).
--
-- DEFAULT를 두지 않는다 — direct 합류 때는 구버전 파드의 insert가 tag_detected_at을 모르는 문제가
-- 있어 DEFAULT now()가 필요했지만, 여기서는 반대다: 롤링 창의 구버전 파드가 만드는 행은 tagged나
-- direct 산지이고 hashtag 성분이 없는 것이 정답이라 NULL이 그대로 옳다. 기존 행 백필도 없다.
ALTER TABLE brand_tagged_post ADD COLUMN hashtag_detected_at timestamptz;

-- 편입 상한(브랜드당 1000) 카운트와 2단계 재수집 모수 조회용 부분 인덱스.
CREATE INDEX brand_tagged_post_hashtag_idx
    ON brand_tagged_post (brand_id) WHERE hashtag_detected_at IS NOT NULL;

-- "이 게시물이 어떤 해시태그로 잡혔나" — was 사용자 격리 필터(내 장부 태그 ∩ 매칭 태그)의 재료.
-- 스윕이 같은 게시물을 다른 태그로 재발견하면 행이 누적된다(멱등 upsert).
-- 구 brand_hashtag_post_matched_tags와 같은 모양이지만 FK 대상이 통합 풀로 바뀌었다 — 구 테이블은
-- 이번 릴리스에서 읽기·쓰기만 중단하고 DROP은 다음 릴리스다(expand-contract).
CREATE TABLE brand_post_matched_tag (
    brand_id   bigint      NOT NULL,
    short_code text        NOT NULL,
    tag        text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (brand_id, short_code, tag),
    FOREIGN KEY (brand_id, short_code) REFERENCES brand_tagged_post (brand_id, short_code) ON DELETE CASCADE
);

-- was 격리 필터는 (brand_id, short_code IN (...))로 읽지만, 태그 축 조회(운영 점검·태그별 편입량)도
-- 흔해 구 matched_tags와 같은 보조 인덱스를 둔다.
CREATE INDEX brand_post_matched_tag_tag_idx ON brand_post_matched_tag (brand_id, tag);
