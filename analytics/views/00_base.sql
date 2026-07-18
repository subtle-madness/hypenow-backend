-- base 뷰: raw 테이블·payload를 직접 만지는 유일한 SQL (ARCHITECTURE.md §4-4).
-- 신 크롤러(V15)는 상세 수집 없이 열거만 한다 — 캡션·지표는 raw_media_page(HIKER_V2_CLIPS
-- 릴스 페이지)·raw_profile(SELF_GQL 내장 타임라인) payload 안. 추출 경로는 crawler
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

-- 릴스 페이지 아이템 평탄화 (HIKER_V2_CLIPS). item_ordinal = 페이지 내 위치(원형 불변 → 안정)
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
CROSS JOIN LATERAL (SELECT it.item->'media' AS media) m;

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
FROM analytics.v_base_content_snapshot
ORDER BY content_id, captured_at DESC, id DESC;

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
