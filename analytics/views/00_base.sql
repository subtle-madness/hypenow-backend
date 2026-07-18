-- base 뷰: raw 테이블·payload를 직접 만지는 유일한 SQL (ARCHITECTURE.md §4-4).
-- crawler가 payload 구조를 바꾸면 이 파일만 고친다.
--
-- 2026-07-18 개편 크롤러 스키마(raw_media_page·influencer) 재작성 — 07-17 e2e 검증 초안
-- (오라클 e2e-raw-pg 적용본)을 회수·보강한 것. 출력 계약(컬럼 이름·의미)은 구 00_base와
-- 동일하게 유지해 하위 뷰(01·02·03·10·20)·미러·record 계약을 무변경으로 통과시킨다.
-- ※ 테스트 하니스(seed/dummy.sql·test/*.test.sql)는 아직 구스키마 — 신스키마 시드 재작성 필요.
CREATE SCHEMA IF NOT EXISTS analytics;

-- 계정별 최신 프로필 — influencer_id가 계정 식별자. username·followers는 crawler가
-- 소스별 추출(ProfileExtractor)로 채운 일반 컬럼이라 그대로 노출한다.
-- 나머지 추출 컬럼의 payload는 수집 소스별 3형태라 전부 3경로 COALESCE:
--   HIKER_MOBILE  {user:{full_name,...}}          — 모바일 API 형태
--   SELF_GQL      {data:{user:{full_name,...}}}   — 웹 GQL 형태 (카운트는 edge_*.count)
--   DATALIKERS    {full_name,...}                 — 평탄 유저 객체
-- 신규 컬럼은 기존 컬럼 뒤에 추가 (CREATE OR REPLACE VIEW는 기존 위치의 컬럼명 변경을 허용하지 않음).
CREATE OR REPLACE VIEW analytics.v_base_profile AS
SELECT DISTINCT ON (influencer_id)
  influencer_id AS account_id,
  username,
  followers,
  captured_at,
  COALESCE(payload#>>'{user,full_name}',
           payload#>>'{data,user,full_name}',
           payload->>'full_name') AS display_name,
  COALESCE(payload#>>'{user,profile_pic_url}',
           payload#>>'{user,hd_profile_pic_url_info,url}',
           payload#>>'{data,user,profile_pic_url}',
           payload#>>'{data,user,profile_pic_url_hd}',
           payload->>'profile_pic_url') AS profile_image_url,
  COALESCE(payload#>>'{user,following_count}',
           payload#>>'{data,user,edge_follow,count}',
           payload->>'following_count')::bigint AS follows_count,
  COALESCE(payload#>>'{user,media_count}',
           payload#>>'{data,user,edge_owner_to_timeline_media,count}',
           payload->>'media_count')::bigint AS posts_count,
  COALESCE(payload#>>'{user,biography}',
           payload#>>'{data,user,biography}',
           payload->>'biography') AS biography,
  COALESCE(payload#>>'{user,external_url}',
           payload#>>'{data,user,external_url}',
           payload->>'external_url') AS external_link
FROM raw_profile
ORDER BY influencer_id, captured_at DESC, id DESC;

-- 상세 스냅샷 이력 — 구 raw_post_detail 대체: raw_media_page(계정별 미디어 페이지)를 평탄화.
-- id는 (페이지 id × 100000 + 페이지 내 순번)로 합성 — 유일·페이지 단조, 정렬 주키는 captured_at.
CREATE OR REPLACE VIEW analytics.v_base_detail_history AS
SELECT
  rmp.id * 100000 + itm.ord AS id,
  c.id AS content_id,
  (itm.media->>'like_count')::bigint    AS likes,
  (itm.media->>'comment_count')::bigint AS comments_count,
  -- 조회수 = play_count, 폴백 ig_play_count. 사진·캐러셀은 둘 다 없어 NULL (§6 규칙 유지)
  COALESCE((itm.media->>'play_count')::bigint, (itm.media->>'ig_play_count')::bigint) AS views,
  rmp.captured_at
FROM raw_media_page rmp
CROSS JOIN LATERAL (
  SELECT e.value->'media' AS media, e.ordinality AS ord
  FROM jsonb_array_elements(rmp.payload#>'{response,items}') WITH ORDINALITY e
) itm
JOIN content c ON c.short_code = itm.media->>'code';

-- 콘텐츠별 최신 상세 (메타 포함) — 최신 수집 페이지의 미디어 아이템.
-- 썸네일만 예외로 "null 아닌 최신값": 재수집 페이지에 image_versions2가 빠지는 엣지가 있어
-- (실측 DZjhKALAgx1) 최신 스냅샷 고정 시 썸네일이 null로 퇴행한다.
CREATE OR REPLACE VIEW analytics.v_base_detail AS
WITH item AS (
  SELECT c.id AS content_id, itm.media, rmp.captured_at, rmp.id AS page_id
  FROM raw_media_page rmp
  CROSS JOIN LATERAL (
    SELECT e.value->'media' AS media
    FROM jsonb_array_elements(rmp.payload#>'{response,items}') e
  ) itm
  JOIN content c ON c.short_code = itm.media->>'code'
), latest AS (
  SELECT DISTINCT ON (content_id) *
  FROM item
  ORDER BY content_id, captured_at DESC, page_id DESC
), thumb AS (
  SELECT DISTINCT ON (content_id)
    content_id,
    media#>>'{image_versions2,candidates,0,url}' AS thumbnail_url
  FROM item
  WHERE media#>'{image_versions2,candidates,0,url}' IS NOT NULL
  ORDER BY content_id, captured_at DESC, page_id DESC
)
SELECT
  l.content_id,
  l.media#>>'{caption,text}' AS caption,
  (l.media->>'like_count')::bigint    AS likes,
  (l.media->>'comment_count')::bigint AS comments_count,
  COALESCE((l.media->>'play_count')::bigint, (l.media->>'ig_play_count')::bigint) AS views,
  (l.media->>'video_duration')::numeric AS video_duration,
  -- 구 Apify 어휘로 매핑 (1 사진 / 2 영상 / 8 캐러셀)
  CASE l.media->>'media_type'
    WHEN '1' THEN 'Image' WHEN '2' THEN 'Video' WHEN '8' THEN 'Sidecar'
    ELSE l.media->>'media_type' END AS media_type,
  l.captured_at,
  t.thumbnail_url,
  'https://www.instagram.com/p/' || (l.media->>'code') || '/' AS original_url
FROM latest l
LEFT JOIN thumb t USING (content_id);

-- 콘텐츠 메타 — 개편으로 카테고리·광고 컬럼이 content에서 빠짐(캡션 LLM 분류로 이관).
-- 계약 유지를 위해 NULL/false로 노출: 카테고리 집계는 빈 결과, ad 비교 블록은 비활성이 된다.
CREATE OR REPLACE VIEW analytics.v_base_content AS
SELECT
  id AS content_id,
  short_code,
  content_type,
  owner_username,
  uploaded_at,
  NULL::bigint AS category_id,
  NULL::text   AS main_group,
  NULL::text   AS subcategory,
  NULL::text   AS discovery_keyword,
  false        AS ad_marked
FROM content;

-- 댓글 평탄화 (신 스키마에도 raw_comment 테이블·generated 컬럼 동일 — 현재 0행)
CREATE OR REPLACE VIEW analytics.v_base_comment AS
SELECT
  id AS comment_id,
  content_id,
  writer,
  text,
  (payload->>'likesCount')::bigint AS like_count,
  written_at
FROM raw_comment;
