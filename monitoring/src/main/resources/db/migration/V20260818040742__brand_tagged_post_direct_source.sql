-- 브랜드 direct 게시물 파이프라인 통합(2026-08-18 설계 §3-1) — expand 단계, nullable ADD만.
-- source 단일 enum을 두지 않는 이유: 한 게시물이 태그 발견분이면서 동시에 직접 등록분일 수 있고
-- PK가 (brand_id, short_code)라 행이 하나뿐이다. 단일 값으로 접으면 취소 시 태그 발견 사실을 잃는다.
-- 응답의 source는 direct_registered_at IS NOT NULL 로 파생한다(direct 우선 = 현행 mergeByShortcode 규칙).
--
-- tag_detected_at의 DEFAULT now()는 롤링 배포 전용이다 — 구버전 파드의 TaggedPostRepository.insert는
-- 이 컬럼을 모르므로 DEFAULT가 채워야 한다. direct 경로는 명시적 NULL로 DEFAULT를 무력화한다.
-- contract 단계에서 DEFAULT를 제거한다.
ALTER TABLE brand_tagged_post
    ADD COLUMN tag_detected_at      timestamptz DEFAULT now(),
    ADD COLUMN direct_registered_at timestamptz;

-- 기존 행은 전부 태그 열거 산지다. 백필을 빠뜨리면 열거 깊이 판정(trackedPosts)의 가드가
-- 전 행을 제외해 다음 스윕이 14일 깊이만 열고 티어 2~4가 통째로 멈춘다.
UPDATE brand_tagged_post SET tag_detected_at = first_seen_at WHERE tag_detected_at IS NULL;

-- direct 2단계 스윕의 모수 조회용 부분 인덱스(전체 행 대비 극소수).
CREATE INDEX brand_tagged_post_direct_idx
    ON brand_tagged_post (brand_id) WHERE direct_registered_at IS NOT NULL;
