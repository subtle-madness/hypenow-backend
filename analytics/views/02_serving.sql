-- 서빙 형태 뷰: 미러 테이블과 1:1 (컬럼 이름·순서 = Flyway DDL = contract-analysis record).
-- 컬럼을 바꾸면 세 곳을 같은 PR에서 바꾼다 (ARCHITECTURE.md §4-5).

-- hype_score v2 (2026-07-20 재설계 — v1 하드캡 방식(min(x,1) 정규화) 폐기. 스펙 docs/.../2026-07-20-hype-score-v2-redesign).
-- 결과 0~100. 두 서빙 뷰(v_contents·v_content_metric_snapshots)가 공유 — 신선도 기준 시각만 호출부가 정한다.
-- 하드캡을 걷어내 "도달·참여 높을수록 항상 점수 ↑"(연속·무제한 로그 축), 타입별 앵커로 피드·릴스 동일 스케일 비교.
--   연속 축(무제한): reach = ln(1 + views/(followers+1000))
--                    engage(릴스) = ln(1 + ((likes+comments×3)/(followers+1000)) / e0)  -- v2.1(2026-07-20): 조회수→팔로워 정규화(저조회수 뭉침 해소). 조회수는 도달 축에만.
--                    engage(피드) = ln(1 + ((likes+comments×3)/(followers+1000)) / f0)   -- 피드는 views 없음
--   합성 Q: 릴스 = wr·reach + we·engage ,  피드 = engage(피드)
--   qf = Q · 0.5^(경과일/halflife)      -- elapsed_days는 호출부가 계산해 넘김, 음수 클램프는 함수 안(GREATEST 0)
--   점수 = qf를 타입별 4점 앵커로 구간 선형 매핑(p05→10·p50→45·p90→80·p99→97, [0,100] 클램프).
--   앵커값은 운영 분석 집합(v_analysis_candidates) 산출·동결 — 릴스는 2026-07-20 v2.1 재적합(팔로워 정규화 분포). 모집단 이동 시 재보정(스펙 §6·2026-07-20-reels-hype-engage-follower-normalization §Task3).
-- 튜닝 상수는 함수가 app_setting에서 직접 읽는다(STABLE) — 호출부는 6-인자로 단순, 재배포 없이 튜닝.
--   키: hype-fresh-halflife-days(14)·hype-reels-e0(0.01: v2.1부터 팔로워당 참여 기준, 릴스 참여율 중앙값≈0.0094)·hype-feed-f0(0.03)·hype-reach-weight(1)·hype-engage-weight(1)
--       ·hype-anchor-{reels,feed}-{p05,p50,p90,p99}. 미설정/0이면 함수 내 COALESCE 기본값(단일 소스).
-- NULL 규칙: likes·comments 중 NULL → NULL, 릴스인데 views NULL → NULL (피드 조회수 항상 NULL은 정상 — CLAUDE.md 함정).
CREATE OR REPLACE FUNCTION analytics.hype_score(
  content_type text, views bigint, likes bigint, comments bigint,
  followers bigint, elapsed_days numeric
) RETURNS bigint
LANGUAGE sql STABLE AS $$
  WITH s AS (
    SELECT
      COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-fresh-halflife-days'),0),14) AS hl,
      COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-reels-e0'),0),0.01)          AS e0,
      COALESCE(NULLIF((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-feed-f0'),0),0.03)           AS f0,
      COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-reach-weight'),1)                   AS wr,
      COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-engage-weight'),1)                  AS we,
      CASE WHEN content_type='reels'
        THEN COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-reels-p05'),0.0736)
        ELSE COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-feed-p05'),0.0111) END  AS a05,
      CASE WHEN content_type='reels'
        THEN COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-reels-p50'),0.7379)
        ELSE COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-feed-p50'),0.1398) END  AS a50,
      CASE WHEN content_type='reels'
        THEN COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-reels-p90'),2.6312)
        ELSE COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-feed-p90'),0.6746) END  AS a90,
      CASE WHEN content_type='reels'
        THEN COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-reels-p99'),5.5619)
        ELSE COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-feed-p99'),1.4890) END  AS a99
  ),
  c AS (
    SELECT s.*,
      (CASE WHEN content_type='reels'
        THEN s.wr * ln(1 + views::numeric/(COALESCE(followers,0)+1000))
           + s.we * ln(1 + ((likes + comments*3)::numeric/(COALESCE(followers,0)+1000))/s.e0)
        ELSE ln(1 + ((likes + comments*3)::numeric/(COALESCE(followers,0)+1000))/s.f0)
      END) * power(0.5, GREATEST(elapsed_days,0)/s.hl) AS qf
    FROM s
  )
  SELECT CASE
    WHEN likes IS NULL OR comments IS NULL OR (content_type='reels' AND views IS NULL) THEN NULL
    ELSE GREATEST(LEAST(round(
      CASE
        WHEN qf <= a05 THEN 10*qf/NULLIF(a05,0)
        WHEN qf <= a50 THEN 10 + 35*(qf-a05)/NULLIF(a50-a05,0)
        WHEN qf <= a90 THEN 45 + 35*(qf-a50)/NULLIF(a90-a50,0)
        WHEN qf <= a99 THEN 80 + 17*(qf-a90)/NULLIF(a99-a90,0)
        ELSE 97 + 3*(qf-a99)/NULLIF(a99-a90,0)
      END), 100), 0)::bigint
  END
  FROM c
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
                       extract(epoch FROM (now() - e.uploaded_at)) / 86400.0) AS hype_score,
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
                       extract(epoch FROM (h.captured_at - e.uploaded_at)) / 86400.0) AS hype_score
FROM analytics.content_snapshot_cache h
JOIN analytics.v_serving_content e USING (content_id)
LEFT JOIN analytics.v_base_profile pr ON pr.username = e.owner_username;

-- 구 hype_score 오버로드 정리(멱등). v2는 6-인자로 되돌렸고(app_setting 내부 조회), 위 두 뷰를 6-인자로
-- 재정의한 뒤라 구 시그니처 의존성이 끊겨 CASCADE 없이 드롭된다(신 DB에선 no-op).
-- 10-인자=v1(운영 배포본), 7-인자=v1 중간본. (6-인자는 지금 새로 만든 v2라 드롭 대상 아님.)
DROP FUNCTION IF EXISTS analytics.hype_score(text, bigint, bigint, bigint, bigint, numeric, numeric, numeric, numeric, numeric);
DROP FUNCTION IF EXISTS analytics.hype_score(text, bigint, bigint, bigint, bigint, numeric, numeric);
