-- 결함 ① 대응: profile_meta에 이미지 아카이브 3컬럼 추가(nullable, additive — expand-contract expand 단계).
-- profile_meta는 username PK로 target 상태와 무관하게 영구 존속하므로, 종료(CANCELED/EXPIRED)된
-- target도 이 컬럼이 채워지면 자동으로 커버된다(findActive() 무변경 — 설계 스펙 §3-1).
ALTER TABLE profile_meta
    -- OCI 오브젝트 스토리지 경로 — monitor-profile/<username>.jpg (was가 '/img/' + 이 값으로 서빙)
    ADD COLUMN image_object_path text,
    -- 원본 CDN URL 경로의 마지막 세그먼트 — 재다운로드 판정 기준(쿼리스트링 제외, ImageArchiveJob.profileChanged와 동일 관용구)
    ADD COLUMN image_source_name text,
    -- 마지막 아카이브 성공 시각
    ADD COLUMN image_archived_at timestamptz;
