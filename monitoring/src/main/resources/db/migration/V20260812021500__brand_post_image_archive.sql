-- 브랜드 게시물 썸네일 아카이브(트랙 KK 확장 — post_meta V20260801064345와 동형 이식).
-- brand_post_meta.thumbnail_url·brand_hashtag_post.thumbnail_url은 인스타 서명 CDN URL이라
-- 며칠~2주면 만료된다 — 프론트가 이 URL을 직접 쓰면 시간이 지난 게시물부터 썸네일이 깨지므로,
-- 아카이브 사본 경로가 서빙 정본이 된다. 3컬럼 nullable additive(expand-contract expand 단계).

-- 브랜드 태그 게시물 표시 메타(short_code PK 전역 테이블 — post_meta와 같은 존속 규칙).
ALTER TABLE brand_post_meta
    -- OCI 오브젝트 스토리지 경로 — monitor-brand-post/<short_code>.jpg (was가 '/img/' + 이 값으로 서빙)
    ADD COLUMN image_object_path text,
    -- 원본 CDN URL 경로의 마지막 세그먼트 — 재다운로드 판정 기준(쿼리스트링 제외, PostThumbnailArchiveJob과 동일 관용구)
    ADD COLUMN image_source_name text,
    -- 마지막 아카이브 성공 시각
    ADD COLUMN image_archived_at timestamptz;

-- 해시태그 발견 게시물((brand_id, short_code) PK — 같은 게시물이 브랜드별 행으로 중복될 수 있어
-- 아카이브 잡은 short_code 기준으로 중복을 접고 UPDATE도 short_code 기준으로 친다).
ALTER TABLE brand_hashtag_post
    -- OCI 오브젝트 스토리지 경로 — monitor-hashtag-post/<short_code>.jpg (was가 '/img/' + 이 값으로 서빙)
    ADD COLUMN image_object_path text,
    -- 원본 CDN URL 경로의 마지막 세그먼트 — 재다운로드 판정 기준(쿼리스트링 제외)
    ADD COLUMN image_source_name text,
    -- 마지막 아카이브 성공 시각
    ADD COLUMN image_archived_at timestamptz;
