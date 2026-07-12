-- 서빙 형태 뷰: 미러 테이블과 1:1 (컬럼 이름·순서 = Flyway DDL = contract-analysis record).
-- 컬럼을 바꾸면 세 곳을 같은 PR에서 바꾼다 (ARCHITECTURE.md §4-5).

-- 계정 (자연키 handle = 인스타 username)
CREATE OR REPLACE VIEW analytics.v_accounts AS
SELECT
  username AS handle,
  display_name,
  profile_image_url,
  followers
FROM analytics.v_base_profile;

-- 콘텐츠 팩트 (상세 수집 완료된 콘텐츠만 — INNER JOIN 의도).
-- hype_score: 릴스=조회수, 피드=좋아요+댓글 (노션 스키마 확정안). 릴스인데 views NULL이면 NULL — 정렬은 NULLS LAST.
CREATE OR REPLACE VIEW analytics.v_contents AS
SELECT
  c.short_code,
  c.owner_username AS account_handle,
  d.thumbnail_url,
  d.caption,
  c.uploaded_at AS posted_at,
  lower(c.content_type) AS content_type,
  d.video_duration,
  d.original_url,
  d.views,
  d.likes,
  d.comments_count AS comments,
  CASE WHEN lower(c.content_type) = 'reels' THEN d.views
       ELSE d.likes + d.comments_count END AS hype_score
FROM analytics.v_base_content c
JOIN analytics.v_base_detail d USING (content_id);

-- 댓글 (작성자는 마스킹해 서빙 — 원문 계정명은 raw에만 둔다)
CREATE OR REPLACE VIEW analytics.v_content_comments AS
SELECT
  m.comment_id AS id,
  c.short_code,
  left(m.writer, 3) || '***' AS author_masked,
  m.text AS body,
  m.like_count
FROM analytics.v_base_comment m
JOIN analytics.v_base_content c USING (content_id);
