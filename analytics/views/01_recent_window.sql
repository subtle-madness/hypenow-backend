-- 최근 N개 윈도우 (ARCHITECTURE.md §4-1): 모든 계정 단위 지표의 공통 밑판.
-- N은 app_setting 'analytics.recent-window' (기본 12) — 재배포 없이 런타임 조정.
-- 서빙 모수 필터의 진입점: 뷰티 인플루언서(QUALIFIED ∧ beauty ∧ ¬beauty_company)의
-- ENUMERATION 콘텐츠 중 스냅샷 있는 것만 (INNER JOIN 의도 — 스펙 2026-07-17 §5).
-- 구 버전 대비: category_id·main_group 소멸(raw에서 제거 — B4 캡션 분류가 대체 소스),
-- ad_marked는 이름 유지 + 소스만 최신 스냅샷의 is_paid_partnership(릴스 전용, 피드 false).
CREATE OR REPLACE VIEW analytics.v_recent_content AS
WITH ranked AS (
  SELECT
    c.content_id,
    c.short_code,
    c.owner_username,
    c.uploaded_at,
    c.content_type,
    d.paid_partnership AS ad_marked,
    d.likes,
    d.comments_count,
    d.views,
    d.video_duration,
    row_number() OVER (PARTITION BY c.owner_username
                       ORDER BY c.uploaded_at DESC, c.content_id DESC) AS recency_rank
  FROM analytics.v_base_content c
  JOIN analytics.v_base_influencer i ON i.influencer_id = c.influencer_id
  JOIN analytics.v_base_detail d USING (content_id)
  WHERE c.origin = 'ENUMERATION'
    AND i.status = 'QUALIFIED' AND i.beauty AND NOT i.beauty_company
)
SELECT *
FROM ranked
WHERE recency_rank <= COALESCE(
  (SELECT value::int FROM app_setting WHERE key = 'analytics.recent-window'), 12);
