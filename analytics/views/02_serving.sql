-- 서빙 형태 뷰: 미러 테이블과 1:1 (컬럼 이름·순서 = Flyway DDL = contract-analysis record).
-- 컬럼을 바꾸면 세 곳을 같은 PR에서 바꾼다 (ARCHITECTURE.md §4-5).

-- hype_score 산식 (스펙 5.4, 2026-07-15 API 스펙 정렬 — 구 원값 방식(릴스=조회수, 피드=좋아요+댓글) 폐기).
-- 결과 0~100. 두 서빙 뷰(v_contents·v_content_metric_snapshots)가 공유 — 신선도 기준 시각만 호출부가 정한다.
--   릴스: score = round(cbrt(reach × engage × fresh) × 100)
--     reach  = LEAST(ln(1 + views/(followers+1000)) / ln(31), 1)
--     engage = LEAST(LEAST((likes + comments×3)/views, 0.5) / 0.12, 1)
--   피드(views 항상 NULL): er = (likes + comments×3)/(followers+1000), axis = LEAST(LEAST(er, 0.3)/0.10, 1)
--     score = round(cbrt(axis² × fresh) × 100) — 축 제곱으로 릴스(3축 곱)와 스케일 균형.
--   fresh = 0.5 ^ (경과일/7) — elapsed_days는 호출부가 계산해 넘기고, 음수 클램프는 함수 안(GREATEST 0).
-- NULL 규칙: 릴스인데 views NULL → NULL, likes·comments 중 NULL → NULL (피드 조회수 항상 NULL — CLAUDE.md 함정).
--   LEAST/GREATEST는 NULL 인자를 무시해 NULL이 전파되지 않으므로 명시 가드가 필수다.
CREATE OR REPLACE FUNCTION analytics.hype_score(
  content_type text, views bigint, likes bigint, comments bigint,
  followers bigint, elapsed_days numeric
) RETURNS bigint
LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE
    WHEN likes IS NULL OR comments IS NULL
         OR (content_type = 'reels' AND views IS NULL) THEN NULL
    WHEN content_type = 'reels' THEN
      round(power(
        LEAST(ln(1 + views::numeric / (COALESCE(followers, 0) + 1000)) / ln(31), 1)
        * LEAST(LEAST((likes + comments * 3)::numeric / NULLIF(views, 0), 0.5) / 0.12, 1)
        * power(0.5, GREATEST(elapsed_days, 0) / 7.0),
        1.0 / 3.0) * 100)::bigint
    ELSE
      round(power(
        power(LEAST(LEAST((likes + comments * 3)::numeric
                          / (COALESCE(followers, 0) + 1000), 0.3) / 0.10, 1), 2)
        * power(0.5, GREATEST(elapsed_days, 0) / 7.0),
        1.0 / 3.0) * 100)::bigint
  END
$$;

-- 계정 (자연키 handle = 인스타 username)
CREATE OR REPLACE VIEW analytics.v_accounts AS
SELECT
  username AS handle,
  display_name,
  profile_image_url,
  followers,
  external_link
FROM analytics.v_base_profile;

-- 콘텐츠 팩트 (상세 수집 완료된 콘텐츠만 — INNER JOIN 의도).
-- 지표(views·likes·comments·hype_score)는 **업로드 +N일 이후 가장 이른 스냅샷으로 고정**
-- (07-14 정정 ③ — 재크롤로 스냅샷이 누적돼도 서빙 지표는 3일 시점 값 유지, 이후 수집분은 이력 전용).
-- N은 app_setting 'analytics.metric-pin-days' (기본 3) — B3 숙성 가드(게시 후 3일 경과)와 같은 3일 기준.
-- 고정 후보가 없으면(업로드 3일 안에만 수집된 구크롤러 잔재) 최신 스냅샷 폴백 —
-- 개편 크롤러는 3일 미경과 게시물을 수집하지 않으므로 폴백 경로는 소멸 예정.
-- 메타(썸네일·캡션·영상 길이 등)는 최신 스냅샷(v_base_detail) — 썸네일 서명 URL 만료(~4일) 대응.
-- hype_score: analytics.hype_score() (산식·NULL 규칙은 함수 주석) — 신선도는 now() 기준.
-- 미러 갱신 시점이 랭킹 신선도의 기준 시각이다(갱신 주기 = 감쇠 반영 주기). NULL 정렬은 NULLS LAST.
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
  SELECT DISTINCT ON (content_id) content_id, views, likes, comments_count, captured_at
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
  analytics.hype_score(lower(c.content_type), p.views, p.likes, p.comments_count, pr.followers,
                       extract(epoch FROM (now() - c.uploaded_at)) / 86400.0) AS hype_score,
  p.captured_at AS metric_captured_at
FROM analytics.v_base_content c
JOIN analytics.v_base_detail d USING (content_id)
JOIN pinned p USING (content_id)
LEFT JOIN analytics.v_base_profile pr ON pr.username = c.owner_username;

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

-- 지표 스냅샷 이력 (게시물 × 수집 시점 1행). contents는 이 중 고정 스냅샷 1건을 편 것 —
-- 랭킹 기본 경로는 contents, as-of 조회·추이만 이 뷰를 쓴다 (스펙 §3).
-- id = raw_post_detail의 id (자연키).
-- hype 산식은 v_contents와 동일 함수 — 신선도만 captured_at 기준(as-of 화면은 "그 시점의 신선도").
CREATE OR REPLACE VIEW analytics.v_content_metric_snapshots AS
SELECT
  h.id,
  c.short_code,
  h.captured_at,
  h.views,
  h.likes,
  h.comments_count AS comments,
  analytics.hype_score(lower(c.content_type), h.views, h.likes, h.comments_count, pr.followers,
                       extract(epoch FROM (h.captured_at - c.uploaded_at)) / 86400.0) AS hype_score
FROM analytics.v_base_detail_history h
JOIN analytics.v_base_content c USING (content_id)
LEFT JOIN analytics.v_base_profile pr ON pr.username = c.owner_username;
