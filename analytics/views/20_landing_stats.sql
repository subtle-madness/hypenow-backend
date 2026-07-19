-- 랜딩 데이터 투명성 통계 (스펙 6.20) — 항상 정확히 1행 (계정 0명이어도 0으로 채운 1행).
-- 모수 = **뷰티 인플루언서 ∩ 마이크로 구간**(팔로워 3,000 이상 50,000 미만)과 그 계정들의
-- ENUMERATION 콘텐츠 (2026-07-17 신 스키마 스펙 §5 — 랜딩 카피 "뷰티 마이크로 인플루언서"와 정합).
-- 뷰티 필터는 01·02(v_serving_content)와 동일 문구 — 모수를 바꿀 땐 세 곳을 같이.
-- 조회수 집계는 릴스만 (피드는 조회수 미공개 — base 층이 이미 NULL 게이트).
-- 분포는 구간별 '계정 수'까지만 내고 %·합계 100 보정은 was 표현 계층 몫 (§4-2).
-- updated_at: 뷰 실행 = 미러 실행 시각 → "매주 갱신 중" 표기의 근거.
--
-- 컬럼명 주의: followers3k10k 등은 언더스코어 없는 이름이 정본 —
-- MirrorJob.toSnakeCase는 대문자 앞에만 '_'를 넣으므로 record 컴포넌트 followers3k10k가
-- 그대로 컬럼명이 된다 (§4-3: 뷰·DDL을 record 변환 결과에 맞춘다).
-- 타입 주의: sum()/round(avg())는 numeric을 돌려준다 — record가 Long이라 ::bigint 캐스트 필수.
CREATE OR REPLACE VIEW analytics.v_landing_stats AS
WITH micro_account AS (
  SELECT p.username, p.followers
  FROM analytics.v_base_profile p
  JOIN analytics.v_base_influencer i USING (influencer_id)
  WHERE i.status = 'QUALIFIED' AND i.beauty AND NOT i.beauty_company
    AND p.followers >= 3000 AND p.followers < 50000
),
micro_content AS (
  -- CTE 이름이 raw의 실테이블 content를 가리지 않도록 micro_content로 둔다.
  SELECT c.content_id, lower(c.content_type) AS content_type, d.views
  FROM analytics.v_base_content c
  JOIN analytics.v_base_detail d USING (content_id)
  JOIN micro_account m ON m.username = c.owner_username
  WHERE c.origin = 'ENUMERATION'
),
content_agg AS (
  SELECT count(*) AS contents_count,
         COALESCE(sum(views) FILTER (WHERE content_type = 'reels'), 0)::bigint AS total_views,
         COALESCE(round(avg(views) FILTER (WHERE content_type = 'reels')), 0)::bigint AS avg_views
  FROM micro_content
),
account_agg AS (
  -- 구간별 '계정 수'까지만 — %·합계 100 보정은 was 몫. 구간 합 = influencers_count.
  SELECT count(*) AS influencers_count,
         count(*) FILTER (WHERE followers < 10000) AS followers3k10k,
         count(*) FILTER (WHERE followers >= 10000 AND followers < 30000) AS followers10k30k,
         count(*) FILTER (WHERE followers >= 30000) AS followers30k50k
  FROM micro_account
)
-- 집계는 GROUP BY가 없으면 모수가 비어도 항상 1행 → CROSS JOIN 결과도 항상 1행.
SELECT contents_count, influencers_count, total_views, avg_views,
       followers3k10k, followers10k30k, followers30k50k, now() AS updated_at
FROM content_agg CROSS JOIN account_agg;
