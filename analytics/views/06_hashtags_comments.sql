-- 그룹 6: 해시태그·멘션·댓글 (텍스트/감성 분석은 범위 밖 — 나중 Python)

-- 캡션 해시태그별 성과
CREATE OR REPLACE VIEW analytics.v_hashtag_performance AS
SELECT
  tag,
  count(*)                       AS content_count,
  round(avg(engagement_rate), 4) AS avg_engagement_rate
FROM analytics.v_content_performance cp
CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(cp.hashtags, '[]'::jsonb)) AS tag
GROUP BY tag;

-- 멘션(협업/태그)별 빈도
CREATE OR REPLACE VIEW analytics.v_mention_performance AS
SELECT
  mention,
  count(*) AS content_count
FROM analytics.v_content_performance cp
CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(cp.mentions, '[]'::jsonb)) AS mention
GROUP BY mention;

-- 콘텐츠별 댓글 통계 (작성자 다양성·대댓글 비율)
CREATE OR REPLACE VIEW analytics.v_content_comment_stats AS
SELECT
  content_id,
  count(*)                                                              AS comment_count,
  count(DISTINCT writer)                                                AS unique_writers,
  sum((payload->>'repliesCount')::int)                                  AS total_replies,
  round(sum((payload->>'repliesCount')::int)::numeric
        / NULLIF(count(*), 0), 4)                                       AS reply_ratio
FROM raw_comment
GROUP BY content_id;
