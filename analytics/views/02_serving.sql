-- 서빙 형태 뷰: 미러 테이블과 1:1 (컬럼 이름·순서 = Flyway DDL = contract-analysis record).
-- 컬럼을 바꾸면 세 곳을 같은 PR에서 바꾼다 (ARCHITECTURE.md §4-5).

-- hype_score v3 (2026-07-30 — 감쇠를 앵커 매핑 뒤로 이동. 스펙
-- docs/superpowers/specs/2026-07-30-hype-score-v3-decay-after-mapping-design.md).
-- 결과 0~100. 두 서빙 뷰(v_contents·v_content_metric_snapshots)가 공유 — 신선도 기준 시각만 호출부가 정한다.
-- 왜 바꿨나: v2.1까지는 감쇠 후 qf에 앵커를 맞췄는데, qf가 콘텐츠 연령에 의존해서 앵커 캘리브레이션이
-- 코퍼스 연령 구성에 오염됐다 — 피드가 릴스보다 옛날 꼬리가 두꺼워 타입별로 다르게 눌렸고, 그게 발굴
-- 목록 피드 편향의 구조적 원인이었다. 앵커 재적합만으로는 적합 모수를 벗어나면 다시(반대 방향으로)
-- 어긋난다 — 근본 해법은 앵커 기준량 자체를 연령 무관 Q로 바꾸는 것.
--   연속 축(무제한): reach = ln(1 + views/(followers+1000))
--                    engage(릴스) = ln(1 + ((likes+comments×3)/(followers+1000)) / e0)  -- v2.1(2026-07-20): 조회수→팔로워 정규화(저조회수 뭉침 해소). 조회수는 도달 축에만.
--                    engage(피드) = ln(1 + ((likes+comments×3)/(followers+1000)) / f0)   -- 피드는 views 없음
--   합성 Q: 릴스 = wr·reach + we·engage ,  피드 = engage(피드)   -- Q는 연령과 무관(v3~)
--   점수 = clamp(Q를 타입별 4점 앵커로 구간 선형 매핑(p05→10·p50→45·p90→80·p99→97), 0, 100)
--          × 0.5^(경과일/halflife)   -- elapsed_days는 호출부가 계산해 넘김, 음수 클램프는 함수 안(GREATEST 0)
--   클램프는 감쇠 **전** — base가 [0,100]이어야 "품질 백분위 × 신선도"가 성립한다.
--   앵커는 **감쇠 전 Q** 기준·**전체 서빙 코퍼스**로 적합(v3~. v2.1까지는 qf 기준·분석 후보 집합이었다 —
--   구 키(hype-anchor-{reels,feed}-*)에 옛 스펙 값을 넣어도 기준량이 달라 조용히 망가지므로 무시된다).
--   재산출은 analytics/check/hype-anchor-refit.sh(재현 절차를 저장소에 둔 이유는 스펙 §5-2).
-- 튜닝 상수는 함수가 app_setting에서 직접 읽는다(STABLE) — 호출부는 6-인자로 단순, 재배포 없이 튜닝.
--   키: hype-fresh-halflife-days(14)·hype-reels-e0(0.01: v2.1부터 팔로워당 참여 기준, 릴스 참여율 중앙값≈0.0094)·hype-feed-f0(0.03)·hype-reach-weight(1)·hype-engage-weight(1)
--       ·hype-anchor-q-{reels,feed}-{p05,p50,p90,p99}. 미설정/0이면 함수 내 COALESCE 기본값(단일 소스).
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
        THEN COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-reels-p05'),0.1373)
        ELSE COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-feed-p05'),0.0447) END  AS a05,
      CASE WHEN content_type='reels'
        THEN COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-reels-p50'),1.3798)
        ELSE COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-feed-p50'),0.6135) END  AS a50,
      CASE WHEN content_type='reels'
        THEN COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-reels-p90'),4.5716)
        ELSE COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-feed-p90'),1.6320) END  AS a90,
      CASE WHEN content_type='reels'
        THEN COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-reels-p99'),10.3883)
        ELSE COALESCE((SELECT value::numeric FROM app_setting WHERE key='analytics.hype-anchor-q-feed-p99'),3.0144) END  AS a99
  ),
  c AS (
    SELECT s.*,
      (CASE WHEN content_type='reels'
        THEN s.wr * ln(1 + views::numeric/(COALESCE(followers,0)+1000))
           + s.we * ln(1 + ((likes + comments*3)::numeric/(COALESCE(followers,0)+1000))/s.e0)
        ELSE ln(1 + ((likes + comments*3)::numeric/(COALESCE(followers,0)+1000))/s.f0)
      END) AS q
    FROM s
  )
  SELECT CASE
    WHEN likes IS NULL OR comments IS NULL OR (content_type='reels' AND views IS NULL) THEN NULL
    ELSE round(
      GREATEST(LEAST(
        CASE
          WHEN q <= a05 THEN 10*q/NULLIF(a05,0)
          WHEN q <= a50 THEN 10 + 35*(q-a05)/NULLIF(a50-a05,0)
          WHEN q <= a90 THEN 45 + 35*(q-a50)/NULLIF(a90-a50,0)
          WHEN q <= a99 THEN 80 + 17*(q-a90)/NULLIF(a99-a90,0)
          ELSE 97 + 3*(q-a99)/NULLIF(a99-a90,0)
        END, 100), 0)
      * power(0.5, GREATEST(elapsed_days,0)/hl)
    )::bigint
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
  p.captured_at AS metric_captured_at,
  -- 인스타 유료 파트너십 태그(릴스 전용, 피드는 항상 false). 광고 판정의 정본은 캡션 분류
  -- (content_analyses.ad_type)지만, 이 태그는 인스타가 보증하는 확정 사실이라 LLM 프롬프트에
  -- 사실로 실어야 한다 — 안 실으면 태그가 붙은 게시물을 LLM이 organic으로 뒤집는다(실측 87건).
  -- 미러(contents.ad_marked)로 내려야 일상 잡(analysis DB만 읽음)이 쓸 수 있다.
  p.paid_partnership AS ad_marked
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
