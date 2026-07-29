-- celfit-front가 실제 소비하는 /v1 응답 필드 ↔ analysis DB 채움율 보고.
-- 대상: analysis DB (미러·분석 산출물). 뷰 하니스(test/)와 달리 실데이터를 그대로 읽는다.
-- 행 구성은 프론트 소비 지점 기준: 카드·필터(6.1) → 상세 드로어 AI 리포트(6.3) → 인플루언서(6.4/6.5).
-- 타입에만 있고 UI 미소비인 필드(email·external_link 등)와 /v1 미사용 미러(content_metric_snapshots 등)는 싣지 않는다.
-- ※ analytics 어드민 /ui/coverage 페이지(src/.../coverage/CoverageRepository.java)와 매트릭스 정의가 쌍 —
--   항목·판정을 바꾸면 둘 다 고칠 것. (07-19 was /coverage에서 이전 — 내부 화면은 어드민 소속)
-- 구간 구분:
--   · 원본 지표 계열(contents·accounts) — 비어 있으면 실패(가드)
--   · LLM 분석·리포트 계열 — 채움율만 보고 (전부 0이어도 통과)
-- 분석 필드 분모는 전체 콘텐츠 수: 6.1이 분석 완료만 노출하므로 미분석분이 곧 미노출분이다.

WITH
  c AS (SELECT count(*) AS total,
               count(caption)            AS caption,
               count(posted_at)          AS posted_at,
               count(content_type)       AS ctype,
               count(thumbnail_url)      AS thumb,
               count(likes)              AS likes,
               count(comments)           AS comments,
               count(hype_score)         AS hype,
               count(original_url)       AS ourl,
               count(metric_captured_at) AS mcap
        FROM contents),
  cr AS (SELECT count(*) AS total, count(views) AS views, count(video_duration) AS vdur
         FROM contents WHERE content_type = 'reels'),
  cf AS (SELECT count(*) AS total, count(views) AS views FROM contents WHERE content_type = 'feed'),
  a AS (SELECT count(*) AS total,
               count(display_name)      AS dname,
               count(followers)         AS followers,
               count(profile_image_url) AS pimg
        FROM accounts),
  an AS (SELECT count(main_category)          AS category,
                count(sub_categories)         AS subcats,
                count(ad_type)                AS ad_type,
                least(count(detected_brands), count(detected_products)) AS tags,
                count(detected_distributors)  AS distributors,
                least(count(ai_content_summary), count(contents_pattern), count(ai_comment_insight)) AS copy3,
                least(count(recent_reels_avg_views), count(recent12_avg_engagement_rate)) AS baseline,
                                least(count(sponsored_signal_level), count(detected_product_categories), count(vlm_attributes)) AS vlm,
                count(comment_authenticity_grade) AS cauth
         FROM content_analyses),
  v AS (SELECT (SELECT count(*) FROM beauty_taxonomy)     AS taxonomy,
               (SELECT count(*) FROM beauty_distributors) AS dist_vocab),
  cm AS (SELECT count(*) AS total FROM content_comments),
  cc AS (SELECT count(*) AS total FROM comment_classifications),
  acs AS (SELECT count(*) AS total FROM account_content_series),
  su AS (SELECT count(*) AS total FROM account_summaries),
  -- "카피 보유" = 계정별 최신 행이 신 스키마(perf_summary, V40)일 때만 — 행 존재만 보면
  -- 07-27 개편 백필 대상(구 스키마 최신 행)까지 완료로 잘못 잡힌다.
  aa AS (SELECT count(*) AS handles FROM (
    SELECT DISTINCT ON (handle) handle, perf_summary
    FROM account_analyses ORDER BY handle, analyzed_at DESC
  ) latest WHERE perf_summary IS NOT NULL)
SELECT "화면 요소", "소스", "채움", "상태" FROM (
  SELECT 1 AS ord, '계정 핸들·이름·프로필' AS "화면 요소", 'accounts' AS "소스",
         format('%s / %s', least(a.dname, a.pimg), a.total) AS "채움",
         CASE WHEN a.total > 0 AND least(a.dname, a.pimg) = a.total THEN '준비됨' ELSE '누락' END AS "상태"
  FROM a
  UNION ALL
  SELECT 2, '팔로워 수 (표시·구간 필터·ER 분모)', 'accounts.followers',
         format('%s / %s', a.followers, a.total),
         CASE WHEN a.total > 0 AND a.followers = a.total THEN '준비됨' ELSE '누락' END
  FROM a
  UNION ALL
  SELECT 3, '캡션·게시일·콘텐츠 유형 (키워드 검색·최신 정렬)', 'contents',
         format('%s / %s', least(c.caption, c.posted_at, c.ctype), c.total),
         CASE WHEN c.total > 0 AND least(c.caption, c.posted_at, c.ctype) = c.total THEN '준비됨' ELSE '누락' END
  FROM c
  UNION ALL
  SELECT 4, '썸네일', 'contents.thumbnail_url',
         format('%s / %s', c.thumb, c.total),
         CASE WHEN c.thumb = c.total THEN '준비됨' WHEN c.thumb > 0 THEN '일부 누락' ELSE '누락' END
  FROM c
  UNION ALL
  SELECT 5, '조회수 — 릴스', 'contents.views',
         format('%s / %s', cr.views, cr.total),
         CASE WHEN cr.total > 0 AND cr.views = cr.total THEN '준비됨' ELSE '누락' END
  FROM cr
  UNION ALL
  SELECT 6, '조회수 — 피드 (원래 NULL 규칙)', 'contents.views',
         format('%s / %s', cf.views, cf.total), '정상 범위'
  FROM cf
  UNION ALL
  SELECT 7, '좋아요·댓글 수', 'contents.likes / comments',
         format('%s / %s', least(c.likes, c.comments), c.total),
         CASE WHEN c.total > 0 AND least(c.likes, c.comments) = c.total THEN '준비됨' ELSE '누락' END
  FROM c
  UNION ALL
  SELECT 8, 'Hype 스코어 (정렬·Hype 지수 표시)', 'contents.hype_score',
         format('%s / %s', c.hype, c.total),
         CASE WHEN c.total > 0 AND c.hype = c.total THEN '준비됨' ELSE '누락' END
  FROM c
  UNION ALL
  SELECT 9, '영상 길이 — 릴스', 'contents.video_duration',
         format('%s / %s', cr.vdur, cr.total),
         CASE WHEN cr.total > 0 AND cr.vdur = cr.total THEN '준비됨' WHEN cr.vdur > 0 THEN '일부 누락' ELSE '누락' END
  FROM cr
  UNION ALL
  SELECT 10, '원본 링크 (임베드·링크 복사)', 'contents.original_url',
         format('%s / %s', c.ourl, c.total),
         CASE WHEN c.total > 0 AND c.ourl = c.total THEN '준비됨' WHEN c.ourl > 0 THEN '일부 누락' ELSE '누락' END
  FROM c
  UNION ALL
  SELECT 11, '업데이트 시각 (updatedAt)', 'contents.metric_captured_at',
         format('%s / %s', c.mcap, c.total),
         CASE WHEN c.mcap = 0 THEN '없음' WHEN c.mcap < c.total THEN '부분' ELSE '준비됨' END
  FROM c
  UNION ALL
  SELECT 12, '대분류 (카테고리 필터 — UI 렌더 없음)', 'content_analyses.main_category',
         format('%s / %s', an.category, c.total),
         CASE WHEN an.category = 0 THEN '없음' WHEN an.category < c.total THEN '부분' ELSE '준비됨' END
  FROM an, c
  UNION ALL
  SELECT 13, '소분류 (카드 칩·중분류 필터)', 'content_analyses.sub_categories',
         format('%s / %s', an.subcats, c.total),
         CASE WHEN an.subcats = 0 THEN '없음' WHEN an.subcats < c.total THEN '부분' ELSE '준비됨' END
  FROM an, c
  UNION ALL
  SELECT 14, '광고/오가닉 배지·필터', 'content_analyses.ad_type',
         format('%s / %s', an.ad_type, c.total),
         CASE WHEN an.ad_type = 0 THEN '없음' WHEN an.ad_type < c.total THEN '부분' ELSE '준비됨' END
  FROM an, c
  UNION ALL
  SELECT 15, '감지 브랜드·제품 태그', 'content_analyses.detected_brands / detected_products',
         format('%s / %s', an.tags, c.total),
         CASE WHEN an.tags = 0 THEN '없음' WHEN an.tags < c.total THEN '부분' ELSE '준비됨' END
  FROM an, c
  UNION ALL
  SELECT 16, '감지 유통사 (태그·유통사 필터)', 'content_analyses.detected_distributors',
         format('%s / %s', an.distributors, c.total),
         CASE WHEN an.distributors = 0 THEN '없음' WHEN an.distributors < c.total THEN '부분' ELSE '준비됨' END
  FROM an, c
  UNION ALL
  SELECT 17, '필터 어휘 (카테고리·유통사 옵션)', 'beauty_taxonomy / beauty_distributors',
         format('%s행 · %s행', v.taxonomy, v.dist_vocab),
         CASE WHEN v.taxonomy > 0 AND v.dist_vocab > 0 THEN '준비됨' ELSE '누락' END
  FROM v
  UNION ALL
  SELECT 18, '드로어 AI 카피 3종 (댓글 인사이트는 임시 숨김)', 'content_analyses.ai_* / contents_pattern',
         format('%s / %s', an.copy3, c.total),
         CASE WHEN an.copy3 = 0 THEN '없음' WHEN an.copy3 < c.total THEN '부분' ELSE '준비됨' END
  FROM an, c
  UNION ALL
  SELECT 19, '드로어 성과 비교 기준선 (조회수·참여율)', 'content_analyses.recent_*',
         format('%s / %s', an.baseline, c.total),
         CASE WHEN an.baseline = 0 THEN '없음' WHEN an.baseline < c.total THEN '부분' ELSE '준비됨' END
  FROM an, c
  UNION ALL
  SELECT 20, '드로어 카테고리 맥락 (상위%·평균·모수 — was 라이브 계산)', 'content_analyses.main_category',
         format('%s / %s', an.category, c.total),
         CASE WHEN an.category = 0 THEN '없음' WHEN an.category < c.total THEN '부분' ELSE '준비됨' END
  FROM an, c
  UNION ALL
  SELECT 21, '드로어 VLM 분석 (협찬 신호·속성·제품 카테고리)', 'content_analyses.sponsored_signal_* / vlm_attributes 외',
         format('%s / %s', an.vlm, c.total),
         CASE WHEN an.vlm = 0 THEN '없음' WHEN an.vlm < c.total THEN '부분' ELSE '준비됨' END
  FROM an, c
  UNION ALL
  SELECT 22, '드로어 댓글 신뢰도 판정 (배포본 임시 숨김)', 'content_analyses.comment_authenticity_grade',
         format('%s / %s', an.cauth, c.total),
         CASE WHEN an.cauth = 0 THEN '없음' WHEN an.cauth < c.total THEN '부분' ELSE '준비됨' END
  FROM an, c
  UNION ALL
  SELECT 23, '드로어 댓글 원문 (배포본 임시 숨김)', 'content_comments',
         format('%s행', cm.total),
         CASE WHEN cm.total > 0 THEN '준비됨' ELSE '없음' END
  FROM cm
  UNION ALL
  SELECT 24, '드로어 댓글 AI 분류 (배포본 임시 숨김)', 'comment_classifications',
         format('%s / %s', cc.total, cm.total),
         CASE WHEN cc.total = 0 THEN '없음' WHEN cc.total < cm.total THEN '부분' ELSE '준비됨' END
  FROM cc, cm
  UNION ALL
  SELECT 25, '드로어 릴스 추이 차트 (리포트 시계열)', 'account_content_series',
         format('%s행', acs.total),
         CASE WHEN acs.total > 0 THEN '준비됨' ELSE '없음' END
  FROM acs
  UNION ALL
  SELECT 26, '인플루언서 프로필 스탯·지표 요약', 'account_summaries',
         format('%s / %s', su.total, a.total),
         CASE WHEN su.total = 0 THEN '없음' WHEN su.total < a.total THEN '부분' ELSE '준비됨' END
  FROM su, a
  UNION ALL
  SELECT 27, '인플루언서 AI 리포트 카피 5종 (07-27 개편, V40)', 'account_analyses',
         format('%s / %s', aa.handles, a.total),
         CASE WHEN aa.handles = 0 THEN '없음' WHEN aa.handles < a.total THEN '부분' ELSE '준비됨' END
  FROM aa, a
) t ORDER BY ord;

-- 구 산출물 content_ranking — 07-12 초기화 전 스키마의 잔재(프론트 미소비, 구 /dashboard만 읽음).
-- 본 쿼리에 넣으면 테이블 부재 시 전체가 죽으므로 was와 동일하게 분리 조회한다(부재 허용).
DO $$
DECLARE
  n bigint;
BEGIN
  IF to_regclass('content_ranking') IS NULL THEN
    RAISE NOTICE '주간 랭킹(content_ranking): 테이블 없음 — 개편 스키마 밖, 정리 대상';
  ELSE
    EXECUTE 'SELECT count(*) FROM content_ranking' INTO n;
    RAISE NOTICE '주간 랭킹(content_ranking): %행 — 옛 산출물, 정리 대상', n;
  END IF;
END $$;

-- 가드: 미러 골격이 비어 있거나 참조가 깨지면 실패시킨다.
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM contents) > 0, 'contents 미러가 비어 있음';
  ASSERT (SELECT count(*) FROM accounts) > 0, 'accounts 미러가 비어 있음';
  -- 매트릭스에서는 빠졌지만(프론트 직접 소비 없음) metric_captured_at의 원천이라 골격 가드는 유지.
  ASSERT (SELECT count(*) FROM content_metric_snapshots) > 0, 'content_metric_snapshots 미러가 비어 있음';
  ASSERT (SELECT count(*) FROM beauty_taxonomy) > 0, 'beauty_taxonomy 어휘가 비어 있음';
  ASSERT (SELECT count(*) FROM beauty_distributors) > 0, 'beauty_distributors 어휘가 비어 있음';
  ASSERT NOT EXISTS (
    SELECT 1 FROM contents c LEFT JOIN accounts a ON a.handle = c.account_handle
    WHERE a.handle IS NULL
  ), '계정 없는 콘텐츠 존재 — contents.account_handle ↛ accounts.handle';
END $$;
