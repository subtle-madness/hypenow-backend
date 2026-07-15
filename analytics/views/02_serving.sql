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
-- 지표(views·likes·comments·hype_score)는 **업로드 +N일 이후 가장 이른 스냅샷으로 고정**
-- (07-14 정정 ③ — 재크롤로 스냅샷이 누적돼도 서빙 지표는 3일 시점 값 유지, 이후 수집분은 이력 전용).
-- N은 app_setting 'analytics.metric-pin-days' (기본 3) — B3 숙성 가드(게시 후 3일 경과)와 같은 3일 기준.
-- 고정 후보가 없으면(업로드 3일 안에만 수집된 구크롤러 잔재) 최신 스냅샷 폴백 —
-- 개편 크롤러는 3일 미경과 게시물을 수집하지 않으므로 폴백 경로는 소멸 예정.
-- 메타(썸네일·캡션·영상 길이 등)는 최신 스냅샷(v_base_detail) — 썸네일 서명 URL 만료(~4일) 대응.
-- hype_score: 릴스=조회수, 피드=좋아요+댓글 (노션 스키마 확정안). 릴스인데 views NULL이면 NULL — 정렬은 NULLS LAST.
-- 피드도 likes·comments 중 하나라도 NULL이면 합산이 NULL로 전파된다 (피드 게시물은 조회수가 항상 NULL — CLAUDE.md 함정).
CREATE OR REPLACE VIEW analytics.v_contents AS
WITH snap AS (
  SELECT h.id, h.content_id, h.views, h.likes, h.comments_count, h.captured_at,
         h.captured_at >= c.uploaded_at + make_interval(days => COALESCE(
           (SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3)) AS matured
  FROM analytics.v_base_detail_history h
  JOIN analytics.v_base_content c USING (content_id)
),
pinned AS (
  -- 성숙(matured) 스냅샷이 있으면 그중 가장 이른 것, 없으면 최신 것.
  SELECT DISTINCT ON (content_id) content_id, views, likes, comments_count
  FROM snap
  ORDER BY content_id, matured DESC,
           CASE WHEN matured THEN captured_at END ASC,
           CASE WHEN matured THEN id END ASC,
           captured_at DESC, id DESC
)
SELECT
  c.short_code,
  c.owner_username AS account_handle,
  d.thumbnail_url,
  d.caption,
  c.uploaded_at AS posted_at,
  lower(c.content_type) AS content_type,
  d.video_duration,
  d.original_url,
  p.views,
  p.likes,
  p.comments_count AS comments,
  CASE WHEN lower(c.content_type) = 'reels' THEN p.views
       ELSE p.likes + p.comments_count END AS hype_score
FROM analytics.v_base_content c
JOIN analytics.v_base_detail d USING (content_id)
JOIN pinned p USING (content_id);

-- 댓글 (작성자는 마스킹해 서빙 — 원문 계정명은 raw에만 둔다)
-- author_masked: writer가 NULL이면 결과도 NULL(플레이스홀더 아님) — 현재 시드·실데이터엔 NULL writer 없음.
CREATE OR REPLACE VIEW analytics.v_content_comments AS
SELECT
  m.comment_id AS id,
  c.short_code,
  left(m.writer, 3) || '***' AS author_masked,
  m.text AS body,
  m.like_count
FROM analytics.v_base_comment m
JOIN analytics.v_base_content c USING (content_id);

-- 지표 스냅샷 이력 (게시물 × 수집 시점 1행). contents는 이 중 최신 1건을 편 것 —
-- 랭킹 기본 경로는 contents, as-of 조회·추이만 이 뷰를 쓴다 (스펙 §3).
-- id = raw_post_detail의 id (자연키). hype_score 규칙은 v_contents와 동일.
CREATE OR REPLACE VIEW analytics.v_content_metric_snapshots AS
SELECT
  h.id,
  c.short_code,
  h.captured_at,
  h.views,
  h.likes,
  h.comments_count AS comments,
  CASE WHEN lower(c.content_type) = 'reels' THEN h.views
       ELSE h.likes + h.comments_count END AS hype_score
FROM analytics.v_base_detail_history h
JOIN analytics.v_base_content c USING (content_id);
