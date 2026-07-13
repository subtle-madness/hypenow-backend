-- base 뷰: raw 테이블·payload를 직접 만지는 유일한 SQL (ARCHITECTURE.md §4-4).
-- crawler가 payload 구조를 바꾸면 이 파일만 고친다.
CREATE SCHEMA IF NOT EXISTS analytics;

-- 계정별 최신 프로필
-- 신규 컬럼은 기존 컬럼 뒤에 추가 (CREATE OR REPLACE VIEW는 기존 위치의 컬럼명 변경을 허용하지 않음).
CREATE OR REPLACE VIEW analytics.v_base_profile AS
SELECT DISTINCT ON (account_id)
  account_id,
  username,
  followers,
  captured_at,
  payload->>'fullName'      AS display_name,
  payload->>'profilePicUrl' AS profile_image_url
FROM raw_profile
ORDER BY account_id, captured_at DESC, id DESC;

-- 콘텐츠별 최신 상세. 조회수 = videoPlayCount, 폴백 videoViewCount (§6 데이터 제약).
-- 피드는 두 필드 모두 없어 views가 NULL — 상위 뷰는 NULL 규칙을 항상 의식할 것.
CREATE OR REPLACE VIEW analytics.v_base_detail AS
SELECT DISTINCT ON (content_id)
  content_id,
  caption,
  likes,
  comments_count,
  COALESCE(video_play_count, (payload->>'videoViewCount')::bigint) AS views,
  (payload->>'videoDuration')::numeric AS video_duration,
  payload->>'type'                     AS media_type,
  captured_at,
  payload->>'displayUrl' AS thumbnail_url,
  payload->>'url'        AS original_url
FROM raw_post_detail
ORDER BY content_id, captured_at DESC, id DESC;

-- 상세 스냅샷 이력 — 중복 크롤링 누적분 전체 (최신 1건 선택은 v_base_detail).
-- 조회수 폴백 규칙은 v_base_detail과 동일하게 유지할 것.
CREATE OR REPLACE VIEW analytics.v_base_detail_history AS
SELECT
  id,
  content_id,
  likes,
  comments_count,
  COALESCE(video_play_count, (payload->>'videoViewCount')::bigint) AS views,
  captured_at
FROM raw_post_detail;

-- 콘텐츠 메타 (content 테이블 노출)
CREATE OR REPLACE VIEW analytics.v_base_content AS
SELECT
  id AS content_id,
  short_code,
  content_type,
  owner_username,
  uploaded_at,
  category_id,
  main_group,
  subcategory,
  discovery_keyword,
  ad_marked
FROM content;

-- 댓글 평탄화
CREATE OR REPLACE VIEW analytics.v_base_comment AS
SELECT
  id AS comment_id,
  content_id,
  writer,
  text,
  (payload->>'likesCount')::bigint AS like_count,
  written_at
FROM raw_comment;
