-- 게시물 캡션 원문 보존 (2026-07-30).
-- 배경: 캡션은 raw_media_page·raw_profile의 jsonb 원형에 실재하지만 MediaItemExtractor가
-- 파싱하지 않아 버려졌고, 도달 경로가 5~7단 jsonb 표현식뿐이어서 "캡션이 DB에 없다"는
-- 오조사가 실제로 발생했다. content 단위 최신 1건만 보존 — 전량 스냅샷은 570MB, 최신만 96MB.
-- content에 컬럼을 붙이지 않는 이유: 148k행 백필 UPDATE가 content를 블로트시키고 TOAST를 만든다.
CREATE TABLE content_caption (
    content_id  bigint      PRIMARY KEY REFERENCES content(id) ON DELETE CASCADE,
    caption     text        NOT NULL,   -- 빈 문자열 = 게시물에 캡션 없음(행 존재 = 확인했음)
    source      text        NOT NULL,   -- 어느 원형에서 건졌는지 — 커버리지 추적·사후 검증용
    captured_at timestamptz NOT NULL,   -- 충돌 시 이 값이 더 최신인 쪽이 이긴다
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- 백필 재개 워터마크 — 페이지 id 커서. 기준값은 마이그레이션으로 시드하고(변경 이력 보존),
-- ON CONFLICT DO NOTHING으로 진행 중인 런타임 값을 되돌리지 않는다(V16 관용구).
INSERT INTO app_setting(key, value) VALUES
  ('caption.backfill.media-page-id', '0'),  -- raw_media_page 마지막 처리 id
  ('caption.backfill.profile-id', '0')      -- raw_profile 마지막 처리 id
ON CONFLICT (key) DO NOTHING;
