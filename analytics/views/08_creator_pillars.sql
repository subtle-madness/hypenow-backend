-- 그룹 8: 크리에이터 기둥 (성과 / 진위)
-- 성과 기둥: 크리에이터 x 카테고리 단위로 팔로워 구간 내 ER 백분위·오버퍼폼 비율.
-- 진위 기둥: 댓글 작성자 다양성/초반 몰림/기계적 짧은 댓글 비율로 어뷰징 신호를 드러낸다.

-- 성과 기둥
CREATE OR REPLACE VIEW analytics.v_creator_card AS
WITH creator AS (
  SELECT owner_username, category_id,
         count(*) AS content_count,
         round(avg(engagement_rate),4) AS avg_engagement_rate,
         round(avg(likes),1) AS avg_likes,
         max(followers) AS followers,
         CASE WHEN max(followers) IS NULL THEN 'unknown'
              WHEN max(followers) < 10000 THEN 'micro'
              WHEN max(followers) < 100000 THEN 'mid'
              ELSE 'macro' END AS tier
  FROM analytics.v_content_performance
  GROUP BY owner_username, category_id
),
-- percentile_cont는 ordered-set aggregate라 OVER()로 윈도우 계산이 불가능하므로
-- 구간(카테고리 x 티어) 중앙값을 별도 CTE로 집계한 뒤 조인한다.
tier_median AS (
  SELECT category_id, tier,
         percentile_cont(0.5) WITHIN GROUP (ORDER BY avg_engagement_rate) AS median_er
  FROM creator
  GROUP BY category_id, tier
)
SELECT
  c.owner_username,
  c.category_id,
  c.content_count,
  c.avg_engagement_rate,
  c.avg_likes,
  c.followers,
  c.tier,
  round(percent_rank() OVER (PARTITION BY c.category_id, c.tier ORDER BY c.avg_engagement_rate)::numeric, 4) AS er_percentile,
  round((c.avg_engagement_rate / NULLIF(tm.median_er, 0))::numeric, 4) AS overperform_ratio
FROM creator c
JOIN tier_median tm ON tm.category_id = c.category_id AND tm.tier = c.tier;

-- 진위 기둥: 크리에이터의 전체 콘텐츠에 달린 댓글을 대상으로 신호 계산
CREATE OR REPLACE VIEW analytics.v_creator_authenticity AS
SELECT
  c.owner_username,
  count(*) AS comment_count,
  round(count(DISTINCT rc.writer)::numeric / NULLIF(count(*), 0), 4) AS unique_writer_ratio,
  round(
    sum(CASE
          WHEN rc.written_at ~ '^\d{4}-'
           AND (rc.written_at::timestamptz - c.uploaded_at) BETWEEN interval '0' AND interval '1 hour'
          THEN 1 ELSE 0
        END)::numeric / NULLIF(count(*), 0), 4
  ) AS early_comment_ratio,
  round(
    sum(CASE
          WHEN char_length(trim(coalesce(rc.text, ''))) < 8
            OR rc.text !~ '[[:alnum:]가-힣]'
          THEN 1 ELSE 0
        END)::numeric / NULLIF(count(*), 0), 4
  ) AS generic_comment_ratio
FROM raw_comment rc
JOIN content c ON c.id = rc.content_id
GROUP BY c.owner_username;

-- 크리에이터별 대표(고성과) 콘텐츠 상위 3
CREATE OR REPLACE VIEW analytics.v_creator_top_contents AS
SELECT owner_username, rank_in_creator, short_code, category_id, main_group,
       engagement_rate, likes, views, uploaded_at, content_format
FROM (
  SELECT
    owner_username,
    row_number() OVER (PARTITION BY owner_username ORDER BY engagement_rate DESC NULLS LAST) AS rank_in_creator,
    short_code,
    category_id,
    main_group,
    engagement_rate,
    likes,
    views,
    uploaded_at,
    content_format
  FROM analytics.v_content_performance
) ranked
WHERE rank_in_creator <= 3;

-- 크리에이터별 대표(가장 긴) 댓글 샘플 상위 5 (근거 인용용)
CREATE OR REPLACE VIEW analytics.v_creator_comment_samples AS
SELECT owner_username, sample_rank, writer, comment_text, written_at
FROM (
  SELECT
    c.owner_username,
    row_number() OVER (PARTITION BY c.owner_username ORDER BY char_length(rc.text) DESC NULLS LAST) AS sample_rank,
    rc.writer,
    rc.text AS comment_text,
    rc.written_at
  FROM raw_comment rc
  JOIN content c ON c.id = rc.content_id
) ranked
WHERE sample_rank <= 5;
