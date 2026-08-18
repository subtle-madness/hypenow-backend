-- 해시태그 발견 게시물 작성자 프로필 사진 아카이브(V20260812021500__brand_post_image_archive.sql과
-- 동형 이식). brand_hashtag_post.author_profile_pic_url은 인스타 서명 CDN URL이라 며칠 뒤 만료되면
-- 프론트 아바타가 깨진다 — 아카이브 사본 경로가 서빙 정본이 된다. 3컬럼 nullable additive
-- (expand-contract expand 단계).

ALTER TABLE brand_hashtag_post
    -- OCI 오브젝트 스토리지 경로 — monitor-hashtag-author/<author_username>.jpg (was가 '/img/' + 이 값으로 서빙)
    ADD COLUMN author_image_object_path text,
    -- 원본 CDN URL 경로의 마지막 세그먼트 — image_source_name과 같은 관용구(기록용. 이 잡은 재다운로드
    -- 판정에 이 값을 쓰지 않는다 — HashtagPostAuthorImageArchiveJob 클래스 주석 참고)
    ADD COLUMN author_image_source_name text,
    -- 마지막 아카이브 성공 시각
    ADD COLUMN author_image_archived_at timestamptz;
