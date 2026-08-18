-- base 뷰: raw 테이블·payload를 직접 만지는 유일한 SQL (ARCHITECTURE.md §4-4).
-- 신 크롤러(V15)는 상세 수집 없이 열거만 한다 — 캡션·지표는 raw_media_page(HIKER_V2_CLIPS
-- 릴스 페이지, 임시 전환 기간은 APIFY_ACTOR)·raw_profile(SELF_GQL 내장 타임라인) payload 안. 추출 경로는 crawler
-- MediaItemExtractor·ProfileExtractor와 정합 (스펙 2026-07-17 §4 — 계약은 crawler가 정의).
-- raw_post_detail(LEGACY 전용)·HIKER_GQL_MEDIAS(유휴 경로)·reel_parse(로컬 실험)는 제외.
CREATE SCHEMA IF NOT EXISTS analytics;

-- influencer 노출 — 서빙 모수(뷰티 인플루언서) 필터 재료. 필터 자체는 상위 뷰(01·02·20) 몫.
CREATE OR REPLACE VIEW analytics.v_base_influencer AS
SELECT
  id AS influencer_id,
  username,
  status,
  followers,
  beauty,
  beauty_company,
  beauty_judged_at
FROM influencer;

-- 계정별 최신 프로필 1건. 실컬럼(username·followers) 우선, payload 폴백.
-- 파생 필드는 source별 경로 분기 — SELF_GQL은 data.user, HIKER_MOBILE·DATALIKERS는
-- user 래퍼(없으면 최상위 — ProfileExtractor.user()와 동일), LEGACY_ENVELOPE·기타는 flat 키.
CREATE OR REPLACE VIEW analytics.v_base_profile AS
SELECT DISTINCT ON (influencer_id)
  influencer_id,
  COALESCE(username, payload#>>'{data,user,username}',
           payload#>>'{user,username}', payload->>'username')          AS username,
  COALESCE(followers,
           (payload#>>'{data,user,edge_followed_by,count}')::bigint,
           (payload#>>'{user,follower_count}')::bigint,
           (payload->>'follower_count')::bigint,
           (payload->>'followersCount')::bigint)                       AS followers,
  captured_at,
  CASE
    WHEN source = 'SELF_GQL' THEN payload#>>'{data,user,full_name}'
    WHEN source IN ('HIKER_MOBILE','DATALIKERS')
      THEN COALESCE(payload#>>'{user,full_name}', payload->>'full_name')
    ELSE payload->>'fullName'
  END AS display_name,
  CASE
    WHEN source = 'SELF_GQL' THEN COALESCE(payload#>>'{data,user,profile_pic_url_hd}',
                                           payload#>>'{data,user,profile_pic_url}')
    WHEN source IN ('HIKER_MOBILE','DATALIKERS')
      THEN COALESCE(payload#>>'{user,profile_pic_url}', payload->>'profile_pic_url')
    ELSE payload->>'profilePicUrl'
  END AS profile_image_url,
  CASE
    WHEN source = 'SELF_GQL' THEN (payload#>>'{data,user,edge_follow,count}')::bigint
    WHEN source IN ('HIKER_MOBILE','DATALIKERS')
      THEN COALESCE(payload#>>'{user,following_count}', payload->>'following_count')::bigint
    ELSE (payload->>'followsCount')::bigint
  END AS follows_count,
  CASE
    WHEN source = 'SELF_GQL' THEN (payload#>>'{data,user,edge_owner_to_timeline_media,count}')::bigint
    WHEN source IN ('HIKER_MOBILE','DATALIKERS')
      THEN COALESCE(payload#>>'{user,media_count}', payload->>'media_count')::bigint
    ELSE (payload->>'postsCount')::bigint
  END AS posts_count,
  CASE
    WHEN source = 'SELF_GQL' THEN payload#>>'{data,user,biography}'
    WHEN source IN ('HIKER_MOBILE','DATALIKERS')
      THEN COALESCE(payload#>>'{user,biography}', payload->>'biography')
    ELSE payload->>'biography'
  END AS biography,
  CASE
    WHEN source = 'SELF_GQL' THEN payload#>>'{data,user,external_url}'
    WHEN source IN ('HIKER_MOBILE','DATALIKERS')
      THEN COALESCE(payload#>>'{user,external_url}', payload->>'external_url')
    ELSE payload->>'externalUrl'
  END AS external_link
FROM raw_profile
ORDER BY influencer_id, captured_at DESC, id DESC;

-- 릴스 페이지 아이템 평탄화 (HIKER_V2_CLIPS + APIFY_ACTOR). item_ordinal = 페이지 내 위치(원형 불변 → 안정)
-- — 합성 스냅샷 id 재료. 실DB 전수에서 flat 접두사(1l/1f) 0건 확인 — 평문 키만 파싱.
-- 좋아요 비공개는 -1 센티널로 온다 → NULL(미상)로 정규화 — hype·평균 오염을 base에서 차단.
CREATE OR REPLACE VIEW analytics.v_base_reel_item AS
SELECT
  p.id            AS page_id,
  it.ord          AS item_ordinal,
  p.influencer_id,
  p.captured_at,
  m.media->>'code'                                        AS short_code,
  NULLIF((m.media->>'like_count')::bigint, -1)            AS likes,
  (m.media->>'comment_count')::bigint                     AS comments_count,
  COALESCE((m.media->>'play_count')::bigint,
           (m.media->>'ig_play_count')::bigint)           AS views,
  m.media->'caption'->>'text'                             AS caption,
  m.media#>>'{image_versions2,candidates,0,url}'          AS thumbnail_url,
  (m.media->>'video_duration')::numeric                   AS video_duration,
  COALESCE((m.media->>'is_paid_partnership')::boolean, false) AS paid_partnership
FROM (SELECT * FROM raw_media_page
      WHERE source = 'HIKER_V2_CLIPS'
        AND jsonb_typeof(payload#>'{response,items}') = 'array') p
CROSS JOIN LATERAL jsonb_array_elements(p.payload#>'{response,items}')
  WITH ORDINALITY AS it(item, ord)
CROSS JOIN LATERAL (SELECT it.item->'media' AS media) m
UNION ALL
-- 릴스 액터(APIFY_ACTOR) 아이템 — 임시 전환 기간(2026-08, 오결제 Apify 크레딧 소진) 수집분.
-- payload는 crawler ReelsJob ACTOR 경로가 {"items":[...]} 래퍼로 저장, 필드명은 08-06 실측.
-- likesCount -1(비공개)→NULL 정규화는 Hiker 분기와 동일 이유.
SELECT
  p.id            AS page_id,
  it.ord          AS item_ordinal,
  p.influencer_id,
  p.captured_at,
  it.item->>'shortCode'                                   AS short_code,
  NULLIF((it.item->>'likesCount')::bigint, -1)            AS likes,
  (it.item->>'commentsCount')::bigint                     AS comments_count,
  COALESCE((it.item->>'videoPlayCount')::bigint,
           (it.item->>'videoViewCount')::bigint)          AS views,
  it.item->>'caption'                                     AS caption,
  it.item->>'displayUrl'                                  AS thumbnail_url,
  (it.item->>'videoDuration')::numeric                    AS video_duration,
  COALESCE((it.item->>'paidPartnership')::boolean, false) AS paid_partnership
FROM (SELECT * FROM raw_media_page
      WHERE source = 'APIFY_ACTOR'
        AND jsonb_typeof(payload->'items') = 'array') p
CROSS JOIN LATERAL jsonb_array_elements(p.payload->'items')
  WITH ORDINALITY AS it(item, ord);

-- SELF_GQL 내장 타임라인 노드 평탄화. 타임라인은 피드 전용이 아니다 — product_type='clips'
-- 노드(릴스)가 다수라 릴스 스냅샷 폴백 소스로도 쓴다. video_view_count 0은 미공개 표기 → NULL.
-- 좋아요 비공개 -1도 동일하게 NULL.
CREATE OR REPLACE VIEW analytics.v_base_timeline_item AS
SELECT
  p.id            AS profile_id,
  it.ord          AS item_ordinal,
  p.influencer_id,
  p.captured_at,
  n.node->>'shortcode'                                    AS short_code,
  NULLIF(COALESCE((n.node#>>'{edge_media_preview_like,count}')::bigint,
                  (n.node#>>'{edge_liked_by,count}')::bigint), -1) AS likes,
  (n.node#>>'{edge_media_to_comment,count}')::bigint      AS comments_count,
  NULLIF((n.node->>'video_view_count')::bigint, 0)        AS views,
  n.node#>>'{edge_media_to_caption,edges,0,node,text}'    AS caption,
  n.node->>'display_url'                                  AS thumbnail_url,
  n.node->>'product_type'                                 AS product_type
FROM (SELECT * FROM raw_profile
      WHERE source = 'SELF_GQL'
        AND jsonb_typeof(payload#>'{data,user,edge_owner_to_timeline_media,edges}') = 'array') p
CROSS JOIN LATERAL jsonb_array_elements(p.payload#>'{data,user,edge_owner_to_timeline_media,edges}')
  WITH ORDINALITY AS it(edge, ord)
CROSS JOIN LATERAL (SELECT it.edge->'node' AS node) n;

-- 콘텐츠 메타 (content 테이블 노출 — origin·status·influencer_id 포함)
CREATE OR REPLACE VIEW analytics.v_base_content AS
SELECT
  id AS content_id,
  short_code,
  content_type,
  owner_username,
  influencer_id,
  uploaded_at,
  origin,
  status
FROM content;

-- 지표 스냅샷 이력 (구 v_base_detail_history 후계) — 열거 원형 1건 = 스냅샷 1행,
-- captured_at = 원형 수집 시각(재방문마다 누적 → +3일 고정 규칙 성립).
-- 합성 id = (원본행 id × 1000 + 아이템 서수) × 2 + 소스태그(reel=0, timeline=1) — 유일·안정 bigint.
-- 서수 < 1000 전제(현 크롤러는 원형 1행당 아이템 ~12개) — 초과 시 이웃 id 대역과 겹치지만,
-- 겹침은 content_metric_snapshots 미러 PK 위반으로 시끄럽게 드러난다(무언 오염 아님).
-- views NULL 규칙(§6): FEED는 무조건 NULL(타임라인에 값이 있어도 게이트), 릴스는 소스 값.
-- content_type은 content 테이블이 정본(crawler 판정 우선), 조인 키는 short_code.
CREATE OR REPLACE VIEW analytics.v_base_content_snapshot AS
SELECT
  (r.page_id * 1000 + r.item_ordinal) * 2 AS id,
  c.content_id,
  r.captured_at,
  r.likes,
  r.comments_count,
  CASE WHEN c.content_type = 'REELS' THEN r.views END AS views,
  r.caption,
  r.thumbnail_url,
  r.video_duration,
  r.paid_partnership
FROM analytics.v_base_reel_item r
JOIN analytics.v_base_content c USING (short_code)
UNION ALL
SELECT
  (t.profile_id * 1000 + t.item_ordinal) * 2 + 1 AS id,
  c.content_id,
  t.captured_at,
  t.likes,
  t.comments_count,
  CASE WHEN c.content_type = 'REELS' THEN t.views END AS views,
  t.caption,
  t.thumbnail_url,
  NULL::numeric AS video_duration,
  false AS paid_partnership
FROM analytics.v_base_timeline_item t
JOIN analytics.v_base_content c USING (short_code);

-- 물질화 캐시: v_base_content_snapshot(raw JSON flatten)을 매 쿼리 재계산하지 않도록 주기 갱신본을 담는다.
-- 소비 뷰(v_base_detail·v_pinned_metrics·v_content_metric_snapshots·v_analysis_candidates)는 이 캐시를 읽어
-- flatten을 한 번만 계산(REFRESH 시점)한다 — 미러·분석 후보 쿼리가 수 분→초로. 신선도는 refresh 주기.
-- 갱신: SELECT analytics.refresh_snapshot_cache(); (야간 잡 직전 cron + 필요 시 수동). 컬럼=view와 동일.
CREATE TABLE IF NOT EXISTS analytics.content_snapshot_cache (
  id              bigint PRIMARY KEY,
  content_id      bigint,
  captured_at     timestamptz,
  likes           bigint,
  comments_count  bigint,
  views           bigint,
  caption         text,
  thumbnail_url   text,
  video_duration  numeric,
  paid_partnership boolean
);
CREATE INDEX IF NOT EXISTS idx_snapshot_cache_content_captured
  ON analytics.content_snapshot_cache (content_id, captured_at);

CREATE OR REPLACE FUNCTION analytics.refresh_snapshot_cache() RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE n bigint;
BEGIN
  TRUNCATE analytics.content_snapshot_cache;
  INSERT INTO analytics.content_snapshot_cache
    SELECT * FROM analytics.v_base_content_snapshot;
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $$;

-- 콘텐츠별 최신 스냅샷 1건 (구 v_base_detail 후계). 메타(캡션·썸네일)는 최신 수집분
-- — 인스타 CDN 서명 URL ~4일 만료 대응(§6). 지표 고정(+3일)은 02 서빙 층 몫.
CREATE OR REPLACE VIEW analytics.v_base_detail AS
SELECT DISTINCT ON (content_id)
  content_id,
  caption,
  likes,
  comments_count,
  views,
  video_duration,
  thumbnail_url,
  paid_partnership,
  captured_at
FROM analytics.content_snapshot_cache
ORDER BY content_id, captured_at DESC, id DESC;

-- 콘텐츠별 고정(pin) 지표 1건 — 지표의 공통 밑판(v_contents·v_recent_content 공유).
-- v_base_detail(메타=최신 스냅샷 승)과 분리한다: 지표는 "성숙(+N일)∧지표완비(usable) 중 가장 이른 것"을
-- 골라야 하며(핀 규칙 — 02_serving §), 빈 타임라인 스냅샷(view 비공개 0→NULL)이 더 나중에 수집돼도
-- 옆의 완비 clips 스냅샷을 핀한다. 이 우선순위가 v_base_detail(최신 승)엔 없어, 최근창 경로가
-- 최신 빈 타임라인을 집어 recentReels·baseline·account_summaries 조회수가 NULL로 죽던 버그를 차단.
-- usable = hype 산출 지표 완비(릴스=views·likes·comments, 피드=likes·comments — 피드 views 항상 NULL).
-- N = app_setting 'analytics.metric-pin-days'(기본 3). 정렬은 02의 v_contents 구 인라인 CTE와 동일.
CREATE OR REPLACE VIEW analytics.v_pinned_metrics AS
WITH snap AS (
  SELECT h.id, h.content_id, h.views, h.likes, h.comments_count, h.captured_at,
         h.paid_partnership, h.video_duration,
         h.captured_at >= e.uploaded_at + make_interval(days => COALESCE(
           (SELECT value::int FROM app_setting WHERE key = 'analytics.metric-pin-days'), 3)) AS matured,
         (h.likes IS NOT NULL AND h.comments_count IS NOT NULL
          AND (e.content_type <> 'REELS' OR h.views IS NOT NULL)) AS usable
  FROM analytics.content_snapshot_cache h
  JOIN analytics.v_base_content e USING (content_id)
)
-- 우선순위: ① 성숙∧완비 중 가장 이른 것 → ② 완비 중 최신 → ③ 성숙 중 가장 이른 것 → ④ 최신.
SELECT DISTINCT ON (content_id)
  content_id, views, likes, comments_count, captured_at, paid_partnership, video_duration
FROM snap
ORDER BY content_id,
         (matured AND usable) DESC,
         CASE WHEN matured AND usable THEN captured_at END ASC,
         CASE WHEN matured AND usable THEN id END ASC,
         usable DESC,
         CASE WHEN usable THEN captured_at END DESC,
         CASE WHEN usable THEN id END DESC,
         matured DESC,
         CASE WHEN matured THEN captured_at END ASC,
         CASE WHEN matured THEN id END ASC,
         captured_at DESC, id DESC;

-- 댓글 평탄화 (V8부터 writer/text/written_at 실컬럼, like_count만 payload 추출)
CREATE OR REPLACE VIEW analytics.v_base_comment AS
SELECT
  id AS comment_id,
  content_id,
  writer,
  text,
  (payload->>'likesCount')::bigint AS like_count,
  written_at
FROM raw_comment;

-- crawl_run 노출 — 크롤러 파이프라인의 유료 요청 수(비용 집계 재료). payload 파싱 없음.
-- request_count는 비Apify 소스가 실제로 구매한 요청 수다(V7): Apify 실행은 NULL(결과 건당 과금이라
-- 콜 기반 산출 불가), 무료 소스(profile-self)는 0. 상위 뷰(30)가 이 둘을 제외한다.
CREATE OR REPLACE VIEW analytics.v_base_crawl_run AS
SELECT
  id AS crawl_run_id,
  job,
  actor_id,
  status,
  request_count,
  started_at
FROM crawl_run;
