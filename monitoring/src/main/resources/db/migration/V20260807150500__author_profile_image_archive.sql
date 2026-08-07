-- author_profile에 이미지 아카이브 3컬럼 추가(nullable, additive — expand-contract expand 단계,
-- profile_meta의 V20260730192350과 동형). author_profile은 30일 stale + 재등장 시에만 재조회되는데
-- CDN 서명은 ~4일이면 만료되므로, 매일 upsert가 URL을 되살리는 brand_post_meta와 달리
-- 아카이브 없이는 저장된 profile_pic_url이 대부분의 기간 죽어 있다.
ALTER TABLE author_profile
    -- OCI 오브젝트 스토리지 경로 — monitor-author/<ig_user_id>.jpg (was가 '/img/' + 이 값으로 서빙)
    ADD COLUMN image_object_path text,
    -- 원본 CDN URL 경로의 마지막 세그먼트 — 재다운로드 판정 기준(쿼리스트링 제외, ProfileImageArchiveJob과 동일 관용구)
    ADD COLUMN image_source_name text,
    -- 마지막 아카이브 성공 시각
    ADD COLUMN image_archived_at timestamptz;
