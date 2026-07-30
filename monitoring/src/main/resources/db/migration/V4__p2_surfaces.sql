-- P2 표면 4종(계약 v1.1 §2-6·§3·§6): 댓글·계정 메타·매칭 키워드·kind 확장.
-- 알람 트랙(feat/monitoring-alarm-module)이 V3을 선점하고 있다 —
-- 머지 직전 그 트랙의 develop 반영 여부·번호를 재확인할 것(V3 없이 V4가 적용되는 것 자체는 Flyway상 문제 없음).

-- 추적 게시물 댓글 — 게시물 단위 전량 교체 갱신(계약 §3 post_comment). 캠페인 간 공유.
CREATE TABLE post_comment (
    short_code       text        NOT NULL,
    id               text        NOT NULL,
    author           text        NOT NULL,
    body             text        NOT NULL,
    like_count       bigint      NOT NULL,
    commented_at     timestamptz NOT NULL,
    owner_reply_text text,
    PRIMARY KEY (short_code, id)
);

-- 계정 표시 메타 — 계정 단위 최신 1행(계약 §3 profile_meta). 스냅샷과 달리 이력 없이 upsert.
CREATE TABLE profile_meta (
    username          text        PRIMARY KEY,
    display_name      text,
    profile_image_url text,
    last_uploaded_at  date,
    updated_at        timestamptz NOT NULL
);

-- 매칭된 키워드 배열 — v1.1 이전 감지분은 null(was가 빈 배열로 폴백, 계약 §3 detected_candidate).
ALTER TABLE detected_candidate ADD COLUMN matched_keywords jsonb;

-- was_reader SELECT는 V2의 ALTER DEFAULT PRIVILEGES로 새 테이블에도 자동 적용된다(별도 GRANT 불필요,
-- 계약 §6에서 확인함) — MigrationTest가 실제로 SELECT되는지 검증한다.
