-- 콘텐츠별 기준선 (분석 잡 전용 — 미러 안 함, 분석 시점에 content_analyses로 고정 저장).
-- ER = (likes+comments)/views. views NULL(피드)은 ER NULL → 평균에서 제외.
-- 컬럼 형태는 구 버전 유지(기존 분석 Java 무접촉) — category_* 3컬럼은 main_group 소멸로
-- NULL 상수 (B4 캡션 분류 산출물(analysis DB)이 대체 예정 — 스펙 2026-07-17 §5).
--
-- 07-20 후보 스코프 확장: 계정 단위 집계를 v_analysis_account_baseline(account_handle 키)로 분리했다.
--   최근창 밖 후보(다작 계정의 성숙분)도 분석하되, 계정 평균(recent12_avg_*·recent_reels_avg_views 등)은
--   윈도우 밖 게시물에도 계산 가능하므로 프롬프트 앵커로 붙인다(rank_in_recent_reels만 윈도우 밖에선 무의미).
--   러너/잡은 candidates LEFT JOIN account_baseline(계정 평균) + LEFT JOIN baseline(rank)로 대상을 뽑는다.
--   v_analysis_baseline 자체의 출력 형태·값은 불변 — 계정 집계를 새 뷰에서 가져올 뿐이라 기존 소비자 무접촉.

-- 계정 단위 기준선(최근 N개 윈도우 집계). 행 집합은 구 account_agg와 동치 — 같은 windowed(v_recent_content
-- ∩ v_base_detail)에서 집계한다. captured_at은 집계에 안 쓰지만 조인은 유지해 모수를 정확히 승계.
CREATE OR REPLACE VIEW analytics.v_analysis_account_baseline AS
SELECT
  w.owner_username AS account_handle,
  round(avg(w.views) FILTER (WHERE lower(w.content_type) = 'reels'), 0)                    AS recent_reels_avg_views,
  count(*) FILTER (WHERE lower(w.content_type) = 'reels' AND w.views IS NOT NULL)          AS recent_reels_count,
  count(*)                                                                                  AS recent_contents_count,
  round(avg(round((w.likes + w.comments_count)::numeric / NULLIF(w.views, 0), 4)), 4)      AS recent12_avg_engagement_rate,
  round(avg(w.likes), 0)                                                                    AS recent12_avg_like_count,
  round(avg(w.comments_count), 0)                                                           AS recent12_avg_comment_count,
  NULL::smallint AS category_top_percentile,
  NULL::numeric  AS category_avg_views,
  NULL::bigint   AS category_sample_size
FROM analytics.v_recent_content w
JOIN analytics.v_base_detail d USING (content_id)
GROUP BY w.owner_username;

CREATE OR REPLACE VIEW analytics.v_analysis_baseline AS
WITH windowed AS (
  -- captured_at은 최신 수집분 우선 정렬용 (썸네일 서명 URL 만료 대응).
  SELECT w.*, d.captured_at
  FROM analytics.v_recent_content w
  JOIN analytics.v_base_detail d USING (content_id)
),
reels_rank AS (
  SELECT content_id,
         rank() OVER (PARTITION BY owner_username ORDER BY views DESC NULLS LAST) AS rank_in_recent_reels
  FROM analytics.v_recent_content
  WHERE lower(content_type) = 'reels' AND views IS NOT NULL
)
SELECT
  w.short_code,
  a.recent_reels_avg_views,
  r.rank_in_recent_reels,
  a.recent_reels_count,
  a.recent_contents_count,
  a.recent12_avg_engagement_rate,
  a.recent12_avg_like_count,
  a.recent12_avg_comment_count,
  a.category_top_percentile,
  a.category_avg_views,
  a.category_sample_size,
  w.captured_at
FROM windowed w
JOIN analytics.v_analysis_account_baseline a ON a.account_handle = w.owner_username
LEFT JOIN reels_rank r USING (content_id);
