-- 서빙 이미지 아카이브 매핑 (태스크 J, specs/2026-07-21-image-archive-design.md).
-- 잡 소유 누적 테이블 — 미러(MirrorConfig) 대상 아님 (content_analyses 전례).
-- key: thumbnail=short_code / profile=handle. source_name: 원본 URL 파일명(호스트·서명 제외)
-- — 프로필 실제 교체 감지용(같으면 재다운로드 생략).
CREATE TABLE image_assets (
    kind        text NOT NULL CHECK (kind IN ('thumbnail', 'profile')),
    key         text NOT NULL,
    object_path text NOT NULL,
    source_name text NOT NULL,
    archived_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, key)
);
