-- 업데이트 소식(제품 공지) — 운영팀이 어드민에서 작성, 유저 대시보드 패널에 전 유저 공통 서빙.
-- date 컬럼은 두지 않는다: published_at의 KST 달력 날짜를 응답 시점에 파생(프론트 변경요청서 §5).
CREATE TABLE app.notices (
    id           bigserial PRIMARY KEY,
    title        text        NOT NULL,
    published_at timestamptz NOT NULL,  -- 정렬·NEW 배지·예약 발행 판정 기준
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notices_published_idx ON app.notices (published_at DESC);

CREATE TABLE app.notice_items (
    id         bigserial PRIMARY KEY,
    notice_id  bigint NOT NULL REFERENCES app.notices(id) ON DELETE CASCADE,
    position   int    NOT NULL,           -- 표시 순서(어드민 작성 순서 그대로)
    tag        text   NOT NULL CHECK (tag IN ('new','changed','improved','fixed')),
    summary    text   NOT NULL,
    body       text   NOT NULL,           -- 빈 문자열 허용(그 경우 프론트가 펼침 없는 한 줄로 그림)
    link_href  text,
    link_label text,
    CHECK ((link_href IS NULL) = (link_label IS NULL))  -- 링크는 href·label 쌍으로만
);
CREATE INDEX notice_items_notice_idx ON app.notice_items (notice_id, position);

CREATE TABLE app.notice_seen (
    user_id      bigint PRIMARY KEY REFERENCES app.users(id) ON DELETE CASCADE,
    last_seen_at timestamptz NOT NULL
);
