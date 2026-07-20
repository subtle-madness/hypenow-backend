-- 서빙 형태 뷰: 미러 테이블과 1:1 (컬럼 이름·순서 = Flyway DDL = contract-analysis record).
-- 컬럼을 바꾸면 세 곳을 같은 PR에서 바꾼다 (ARCHITECTURE.md §4-5).

-- hype_score 산식 (스펙 5.4, 2026-07-15 API 스펙 정렬 — 구 원값 방식(릴스=조회수, 피드=좋아요+댓글) 폐기).
-- 결과 0~100. 두 서빙 뷰(v_contents·v_content_metric_snapshots)가 공유 — 신선도 기준 시각만 호출부가 정한다.
--   릴스: score = round(cbrt(reach × engage × fresh) × 100)
--     reach  = LEAST(ln(1 + views/(followers+1000)) / ln(1 + reach_mult), 1)      -- 조회가 팔로워의 reach_mult배면 만점
--     engage = LEAST(LEAST((likes + comments×3)/views, 0.5) / engage_target, 1)   -- 조회 대비 참여율이 engage_target면 만점
--   피드(views 항상 NULL): axis = LEAST(LEAST((likes+comments×3)/(followers+1000), 0.3) / feed_target, 1)
--     score = round(cbrt(axis² × fresh) × 100) — 축 제곱으로 릴스(3축 곱)와 스케일 균형.
--   fresh = 0.5 ^ (경과일/halflife). elapsed_days는 호출부가 계산해 넘기고, 음수 클램프는 함수 안(GREATEST 0).
-- 튜닝 상수 4종은 호출부가 app_setting에서 읽어 넘기고, 미설정(NULL)·오설정(0)이면 함수가 기본값 적용(기본값 단일 소스):
--   halflife_days  'analytics.hype-fresh-halflife-days'  기본 14    — 신선도 반감기(일). ↑ = 오래된 콘텐츠 점수 유지
--   reach_mult     'analytics.hype-reach-target-mult'    기본 3     — 조회수/(팔로워+1000) 이 배수면 reach 만점. ↓ = 점수 ↑
--   engage_target  'analytics.hype-engage-target'        기본 0.04  — 릴스 조회 대비 참여율 만점 기준. ↓ = 점수 ↑
--   feed_target    'analytics.hype-feed-engage-target'   기본 0.035 — 피드 팔로워 대비 참여율 만점 기준. ↓ = 점수 ↑
--   (2026-07-20 재보정: 분석 집합 실측으로 30배/12%/10% → 3배/4%/3.5%, 중앙값 21→40 — ARCHITECTURE §7)
-- NULL 규칙: 릴스인데 views NULL → NULL, likes·comments 중 NULL → NULL (피드 조회수 항상 NULL — CLAUDE.md 함정).
--   LEAST/GREATEST는 NULL 인자를 무시해 NULL이 전파되지 않으므로 명시 가드가 필수다.
CREATE OR REPLACE FUNCTION analytics.hype_score(
  content_type text, views bigint, likes bigint, comments bigint,
  followers bigint, elapsed_days numeric, halflife_days numeric,
  reach_mult numeric, engage_target numeric, feed_target numeric
) RETURNS bigint
LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE
    WHEN likes IS NULL OR comments IS NULL
         OR (content_type = 'reels' AND views IS NULL) THEN NULL
    WHEN content_type = 'reels' THEN
      round(power(
        LEAST(ln(1 + views::numeric / (COALESCE(followers, 0) + 1000))
              / ln(1 + COALESCE(NULLIF(reach_mult, 0), 3)), 1)
        * LEAST(LEAST((likes + comments * 3)::numeric / NULLIF(views, 0), 0.5)
              / COALESCE(NULLIF(engage_target, 0), 0.04), 1)
        * power(0.5, GREATEST(elapsed_days, 0) / COALESCE(NULLIF(halflife_days, 0), 14)),
        1.0 / 3.0) * 100)::bigint
    ELSE
      round(power(
        power(LEAST(LEAST((likes + comments * 3)::numeric / (COALESCE(followers, 0) + 1000), 0.3)
              / COALESCE(NULLIF(feed_target, 0), 0.035), 1), 2)
        * power(0.5, GREATEST(elapsed_days, 0) / COALESCE(NULLIF(halflife_days, 0), 14)),
        1.0 / 3.0) * 100)::bigint
  END
$$;

-- 서빙 모수: 뷰티 인플루언서(QUALIFIED ∧ beauty ∧ ¬beauty_company)의 ENUMERATION 콘텐츠
-- (스펙 2026-07-17 §2 결정 2). 아래 뷰들이 공유하는 필터 밑판 — 미러 안 함.
-- 같은 필터가 01(v_recent_content)·20(micro_account)에도 있다 — 모수를 바꿀 땐 세 곳을 같이.
CREATE OR REPLACE VIEW analytics.v_serving_content AS
SELECT c.content_id, c.short_code, c.owner_username, c.uploaded_at, c.content_type
FROM analytics.v_base_content c
JOIN analytics.v_base_influencer i ON i.influencer_id = c.influencer_id
WHERE c.origin = 'ENUMERATION'
  AND i.status = 'QUALIFIED' AND i.beauty AND NOT i.beauty_company;

-- 계정 (자연키 handle = 인스타 username). 뷰티 모수 ∩ 프로필 보유 (INNER JOIN 의도).
CREATE OR REPLACE VIEW analytics.v_accounts AS
SELECT
  p.username AS handle,
  p.display_name,
  p.profile_image_url,
  p.followers,
  p.external_link
FROM analytics.v_base_profile p
JOIN analytics.v_base_influencer i USING (influencer_id)
WHERE i.status = 'QUALIFIED' AND i.beauty AND NOT i.beauty_company;

-- 콘텐츠 팩트. 지표(views·likes·comments·hype_score)는 **업로드 +N일 이후 가장 이른 스냅샷으로
-- 고정**(07-14 정정 ③ — 열거 재방문으로 스냅샷이 누적돼도 서빙 지표는 3일 시점 값 유지).
-- 지표 핀은 공유 뷰 v_pinned_metrics(00_base) — 최근창 경로(recentReels·baseline)와 같은 규칙을 쓴다.
-- 메타(썸네일·캡션)는 최신 스냅샷(v_base_detail) — 썸네일 서명 URL 만료(~4일) 대응.
-- original_url은 short_code로 합성(신 payload에 url 필드 없음).
-- hype_score 신선도는 now() 기준 — 미러 갱신 시점이 랭킹 신선도의 기준 시각이다.
CREATE OR REPLACE VIEW analytics.v_contents AS
SELECT
  e.short_code,
  e.owner_username AS account_handle,
  d.thumbnail_url,
  d.caption,
  e.uploaded_at AS posted_at,
  lower(e.content_type) AS content_type,
  d.video_duration,
  'https://www.instagram.com/p/' || e.short_code || '/' AS original_url,
  p.views,
  p.likes,
  p.comments_count AS comments,
  analytics.hype_score(lower(e.content_type), p.views, p.likes, p.comments_count, pr.followers,
                       extract(epoch FROM (now() - e.uploaded_at)) / 86400.0,
                       (SELECT value::numeric FROM app_setting WHERE key = 'analytics.hype-fresh-halflife-days'),
                       (SELECT value::numeric FROM app_setting WHERE key = 'analytics.hype-reach-target-mult'),
                       (SELECT value::numeric FROM app_setting WHERE key = 'analytics.hype-engage-target'),
                       (SELECT value::numeric FROM app_setting WHERE key = 'analytics.hype-feed-engage-target')) AS hype_score,
  p.captured_at AS metric_captured_at
FROM analytics.v_serving_content e
JOIN analytics.v_base_detail d USING (content_id)
JOIN analytics.v_pinned_metrics p USING (content_id)
LEFT JOIN analytics.v_base_profile pr ON pr.username = e.owner_username;

-- 댓글 (작성자는 마스킹해 서빙 — 원문 계정명은 raw에만 둔다). 형태 구 버전 동일.
-- 댓글 수집 게이트 off 동안 신규 유입 없음 — 구 시대 잔존 행 서빙은 무해(고아 short_code는 was가 안 씀).
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
-- 랭킹 기본 경로는 contents, as-of 조회·추이만 이 뷰를 쓴다.
-- id = 합성 스냅샷 id (00_base 참조 — 구 시대의 raw_post_detail.id 자연키 대체).
-- hype 산식은 v_contents와 동일 함수 — 신선도만 captured_at 기준(as-of 화면은 "그 시점의 신선도").
CREATE OR REPLACE VIEW analytics.v_content_metric_snapshots AS
SELECT
  h.id,
  e.short_code,
  h.captured_at,
  h.views,
  h.likes,
  h.comments_count AS comments,
  analytics.hype_score(lower(e.content_type), h.views, h.likes, h.comments_count, pr.followers,
                       extract(epoch FROM (h.captured_at - e.uploaded_at)) / 86400.0,
                       (SELECT value::numeric FROM app_setting WHERE key = 'analytics.hype-fresh-halflife-days'),
                       (SELECT value::numeric FROM app_setting WHERE key = 'analytics.hype-reach-target-mult'),
                       (SELECT value::numeric FROM app_setting WHERE key = 'analytics.hype-engage-target'),
                       (SELECT value::numeric FROM app_setting WHERE key = 'analytics.hype-feed-engage-target')) AS hype_score
FROM analytics.content_snapshot_cache h
JOIN analytics.v_serving_content e USING (content_id)
LEFT JOIN analytics.v_base_profile pr ON pr.username = e.owner_username;

-- 구 hype_score 시그니처 정리(멱등). 인자 추가는 CREATE OR REPLACE가 교체 못 하고 오버로드를 남긴다 —
-- 위 두 뷰를 신(10-인자)로 재정의한 뒤라 구 함수 의존성이 끊겨 CASCADE 없이 드롭된다(신 DB에선 no-op).
-- 6-인자=운영 배포본, 7-인자=반감기 중간본(로컬만) 둘 다 정리.
DROP FUNCTION IF EXISTS analytics.hype_score(text, bigint, bigint, bigint, bigint, numeric);
DROP FUNCTION IF EXISTS analytics.hype_score(text, bigint, bigint, bigint, bigint, numeric, numeric);
