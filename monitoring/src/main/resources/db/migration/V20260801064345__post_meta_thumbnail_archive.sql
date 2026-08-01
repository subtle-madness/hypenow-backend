-- 트랙 KK 확장(post 썸네일 동형 이식) — post_meta에도 profile_meta(V20260730192350)와 동일한
-- 이미지 아카이브 3컬럼을 추가한다(nullable, additive — expand-contract expand 단계).
-- post_meta는 short_code PK로 target 상태와 무관하게 영구 존속하므로, profile_meta와 마찬가지로
-- 종료(CANCELED/EXPIRED)된 캠페인의 추적 게시물 썸네일도 자동으로 커버된다(findActive() 무변경).
ALTER TABLE post_meta
    -- OCI 오브젝트 스토리지 경로 — monitor-post/<short_code>.jpg (was가 '/img/' + 이 값으로 서빙)
    ADD COLUMN image_object_path text,
    -- 원본 CDN URL 경로의 마지막 세그먼트 — 재다운로드 판정 기준(쿼리스트링 제외, ProfileImageArchiveJob.sourceName과 동일 관용구)
    ADD COLUMN image_source_name text,
    -- 마지막 아카이브 성공 시각
    ADD COLUMN image_archived_at timestamptz;

-- 결함 ②(V20260730191710)와 동형 — PostMetaRepository가 thumbnail_url을 스킴 검증 없이 저장해 온
-- 기존 오염행을 정정한다. 이후 재스윕에서 PostMetaRepository 정규화(http/https 아니면 null, 기존
-- 유효값 COALESCE 보존)를 통해 정상 URL로 복구되거나 계속 null로 남는다.
UPDATE post_meta
   SET thumbnail_url = NULL
 WHERE thumbnail_url IS NOT NULL
   AND thumbnail_url !~* '^https?://';
