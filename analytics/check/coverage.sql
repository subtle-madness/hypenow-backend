-- content-ranking 프론트 화면 요소 ↔ analysis DB 미러 필드 커버리지 보고.
-- 대상: analysis DB (미러 산출물). 뷰 하니스(test/)와 달리 실데이터를 그대로 읽는다.
-- ※ was의 /coverage 페이지(was/.../coverage/CoverageRepository.java)와 매트릭스 정의가 쌍 —
--   항목·판정을 바꾸면 둘 다 고칠 것.
-- 구간 구분:
--   · 원본 지표 계열(contents·accounts·content_metric_snapshots) — 비어 있으면 실패(가드)
--   · LLM 분석·랭킹 산출 계열 — 미구현 구간이라 채움율만 보고 (전부 0이어도 통과)

WITH
  c AS (SELECT count(*) AS total,
               count(caption)       AS caption,
               count(posted_at)     AS posted_at,
               count(content_type)  AS ctype,
               count(thumbnail_url) AS thumb,
               count(likes)         AS likes,
               count(comments)      AS comments,
               count(hype_score)    AS hype
        FROM contents),
  cr AS (SELECT count(*) AS total, count(views) AS views FROM contents WHERE content_type = 'reels'),
  cf AS (SELECT count(*) AS total, count(views) AS views FROM contents WHERE content_type = 'feed'),
  a AS (SELECT count(*) AS total,
               count(display_name)      AS dname,
               count(followers)         AS followers,
               count(profile_image_url) AS pimg
        FROM accounts),
  s AS (SELECT count(*) AS total, max(captured_at)::date AS latest FROM content_metric_snapshots),
  an AS (SELECT count(ad_type)         AS ad_type,
                count(main_category)   AS category,
                count(detected_brands) AS brands
         FROM content_analyses),
  r AS (SELECT count(*) AS total FROM content_ranking)
SELECT "화면 요소", "소스", "채움", "상태" FROM (
  SELECT 1 AS ord, '계정 핸들·이름·프로필' AS "화면 요소", 'accounts' AS "소스",
         format('%s / %s', least(a.dname, a.pimg), a.total) AS "채움",
         CASE WHEN a.total > 0 AND least(a.dname, a.pimg) = a.total THEN '준비됨' ELSE '누락' END AS "상태"
  FROM a
  UNION ALL
  SELECT 2, '팔로워 수 (팔로워 구간 필터)', 'accounts.followers',
         format('%s / %s', a.followers, a.total),
         CASE WHEN a.total > 0 AND a.followers = a.total THEN '준비됨' ELSE '누락' END
  FROM a
  UNION ALL
  SELECT 3, '캡션·게시일·콘텐츠 유형', 'contents',
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
  SELECT 8, '정렬 점수', 'contents.hype_score',
         format('%s / %s', c.hype, c.total),
         CASE WHEN c.total > 0 AND c.hype = c.total THEN '준비됨' ELSE '누락' END
  FROM c
  UNION ALL
  SELECT 9, '지표 추이 스냅샷', 'content_metric_snapshots',
         format('%s행 · 최신 %s', s.total, s.latest),
         CASE WHEN s.total > 0 THEN '준비됨' ELSE '누락' END
  FROM s
  UNION ALL
  SELECT 10, '참여율(ER)', '(전용 컬럼 없음)', '0',
         '없음 — 분석 뷰에서 계산 필요'
  UNION ALL
  SELECT 11, '광고/오가닉 배지·필터', 'content_analyses.ad_type',
         format('%s / %s', an.ad_type, c.total),
         CASE WHEN an.ad_type = 0 THEN '없음' WHEN an.ad_type < c.total THEN '부분' ELSE '준비됨' END
  FROM an, c
  UNION ALL
  SELECT 12, '키워드·대분류 배지·필터', 'content_analyses.main_category',
         format('%s / %s', an.category, c.total),
         CASE WHEN an.category = 0 THEN '없음' WHEN an.category < c.total THEN '부분' ELSE '준비됨' END
  FROM an, c
  UNION ALL
  SELECT 13, '브랜드·제품·유통사 개수', 'content_analyses.detected_brands',
         format('%s / %s', an.brands, c.total),
         CASE WHEN an.brands = 0 THEN '없음' WHEN an.brands < c.total THEN '부분' ELSE '준비됨' END
  FROM an, c
  UNION ALL
  SELECT 14, '주간 랭킹(집계 기간)', 'content_ranking',
         format('%s행', r.total),
         CASE WHEN r.total = 0 THEN '없음' ELSE '옛 산출물 — 태스크 A 재구축 대상' END
  FROM r
) t ORDER BY ord;

-- 가드: 미러 골격이 비어 있거나 참조가 깨지면 실패시킨다.
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM contents) > 0, 'contents 미러가 비어 있음';
  ASSERT (SELECT count(*) FROM accounts) > 0, 'accounts 미러가 비어 있음';
  ASSERT (SELECT count(*) FROM content_metric_snapshots) > 0, 'content_metric_snapshots 미러가 비어 있음';
  ASSERT NOT EXISTS (
    SELECT 1 FROM contents c LEFT JOIN accounts a ON a.handle = c.account_handle
    WHERE a.handle IS NULL
  ), '계정 없는 콘텐츠 존재 — contents.account_handle ↛ accounts.handle';
END $$;
