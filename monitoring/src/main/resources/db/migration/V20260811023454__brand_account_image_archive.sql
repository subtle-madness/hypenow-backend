-- brand_account에 이미지 아카이브 3컬럼 추가(nullable, additive — expand-contract expand 단계,
-- author_profile의 V20260807150500과 동형). 브랜드 본인 profile_pic_url은 스윕이 매일 재조회하지만
-- 저장값 자체는 인스타 서명 CDN URL이라 며칠~2주면 만료된다 — 프론트가 이 URL을 직접 쓰면
-- 등록 후 시간이 지난 브랜드부터 이미지가 깨지므로, 아카이브 사본 경로가 서빙 정본이 된다.
ALTER TABLE brand_account
    -- OCI 오브젝트 스토리지 경로 — monitor-brand/<ig_user_id>.jpg (was가 '/img/' + 이 값으로 서빙)
    ADD COLUMN image_object_path text,
    -- 원본 CDN URL 경로의 마지막 세그먼트 — 재다운로드 판정 기준(쿼리스트링 제외, AuthorProfileImageArchiveJob과 동일 관용구)
    ADD COLUMN image_source_name text,
    -- 마지막 아카이브 성공 시각
    ADD COLUMN image_archived_at timestamptz;
