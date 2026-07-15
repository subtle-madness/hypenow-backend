-- 댓글 1:1 AI 분류 (분석 층 소유 — 미러 아님, Java가 직접 쓴다).
-- id = 미러 테이블 content_comments.id와 같은 raw 댓글 id (논리 참조, FK 없음).
-- ai_category 어휘는 생산자(분석 층)가 확정하고 was는 전달만 한다 (ARCHITECTURE §4-4).
CREATE TABLE comment_classifications (
    id           bigint PRIMARY KEY,
    short_code   text   NOT NULL,
    ai_category  text   NOT NULL CHECK (ai_category IN
        ('purchase', 'question', 'positive', 'adAware', 'friendTag', 'etc')),
    model        text   NOT NULL,
    classified_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_comment_classifications_short_code ON comment_classifications (short_code);
