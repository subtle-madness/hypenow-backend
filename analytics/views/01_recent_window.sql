-- 최근 N개 윈도우 (ARCHITECTURE.md §4-1): 모든 계정 단위 지표의 공통 밑판.
-- N은 app_setting 'analytics.recent-window' (기본 12) — 재배포 없이 런타임 조정.
-- 서빙 모수 필터의 진입점: 뷰티 ∪ F&B 인플루언서(QUALIFIED ∧ 축 ∧ ¬축회사 — 2026-08-31
-- F&B 서빙 개방)의 ENUMERATION 콘텐츠 중 스냅샷 있는 것만 (INNER JOIN 의도 — 스펙 2026-07-17 §5).
-- 기본 화면 불변은 was 층이 지킨다(무필터=뷰티 명시) — 뷰 모수는 필터로 F&B가 나올 재료.
-- 같은 필터가 02(v_serving_content)에도 있다(뷰 적용 순서상 공유 불가) — 모수를 바꿀 땐 같이.
-- 20(micro_account)은 **의도적으로 뷰티 유지** — 랜딩 노출 숫자는 기본 화면이다(서빙 개방 §4).
-- 구 버전 대비: category_id·main_group 소멸(raw에서 제거 — B4 캡션 분류가 대체 소스),
-- ad_marked는 이름 유지 + 소스는 is_paid_partnership(릴스 전용, 피드 false).
-- 지표(views·likes·comments·ad_marked)는 공유 핀 뷰 v_pinned_metrics에서 가져온다 — v_contents(랭킹)와
-- 같은 "성숙∧완비 우선" 스냅샷을 써, recentReels·baseline·account_summaries가 랭킹과 어긋나지 않는다.
-- (구: v_base_detail 최신 승 → 최신 빈 타임라인 스냅샷이 조회수를 NULL로 밀어내던 버그. 00_base 참조.)
CREATE OR REPLACE VIEW analytics.v_recent_content AS
WITH ranked AS (
  SELECT
    c.content_id,
    c.short_code,
    c.owner_username,
    c.uploaded_at,
    c.content_type,
    p.paid_partnership AS ad_marked,
    p.likes,
    p.comments_count,
    p.views,
    p.video_duration,
    row_number() OVER (PARTITION BY c.owner_username
                       ORDER BY c.uploaded_at DESC, c.content_id DESC) AS recency_rank
  FROM analytics.v_base_content c
  JOIN analytics.v_base_influencer i ON i.influencer_id = c.influencer_id
  JOIN analytics.v_pinned_metrics p USING (content_id)
  WHERE c.origin = 'ENUMERATION'
    AND i.status = 'QUALIFIED'
    -- 서빙 모수 = 뷰티 ∪ F&B (2026-08-31 서빙 개방 §4 — v_analysis_source와 동일 술어)
    AND ( (i.beauty AND NOT i.beauty_company)
       OR (i.fnb    AND NOT i.fnb_company) )
)
SELECT *
FROM ranked
WHERE recency_rank <= COALESCE(
  (SELECT value::int FROM app_setting WHERE key = 'analytics.recent-window'), 12);
