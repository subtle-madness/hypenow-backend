package com.celfit.analytics.coverage;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 수집(raw)→미러·분석(analysis) 커버리지 조회 — was /coverage에서 이전(2026-07-19).
 * 매트릭스는 celfit-front가 실제 소비하는 /v1 응답 필드별 analysis DB 채움율.
 * 기준 코드는 celfit-front 배포본(origin/main) — 로컬 체크아웃이 아니라 배포본과 대조해 갱신한다.
 * 행 구성은 프론트 소비 지점 기준: 카드·필터(6.1) → 상세 드로어 AI 리포트(6.3) → 인플루언서(6.4/6.5).
 * 타입에만 있고 UI 미소비인 필드(email·external_link 등)는 싣지 않는다.
 * content_metric_snapshots 미러는 2026-07-30 제거됨(소비자 부재·미러 시간 절반 차지) — 더는 대상 밖.
 * 배포본에서 "임시 숨김"(주석 처리) 상태인 요소는 행을 유지하고 이름에 표기한다 — 계약(스펙 6.3)은 유효.
 * 매트릭스 정의는 CLI 점검 스크립트(analytics/check/coverage.sql)와 쌍 — 항목이 바뀌면 둘 다 고칠 것.
 */
public class CoverageRepository {

	private static final Logger log = LoggerFactory.getLogger(CoverageRepository.class);

	// 수집 모수 — 신 스키마 서빙 뷰(02_serving.sql)가 정본인 뷰티 인플루언서 필터를 그대로 읽는다.
	// v_contents(지표 고정 계산)가 아닌 v_serving_content를 세는 이유: 분모는 "수집된 서빙 대상"이고 계산 비용도 가볍다.
	private static final String SOURCE_SQL = """
			SELECT (SELECT count(*) FROM v_accounts)        AS accounts,
			       (SELECT count(*) FROM v_serving_content) AS contents
			""";

	// coverage.sql의 보고 쿼리와 동일한 집계 — 상태 판정 CASE도 일치시킨다.
	// 분석 필드 분모는 c.total(전체 콘텐츠): 6.1이 분석 완료만 노출하므로 미분석분이 곧 미노출분이다.
	private static final String MATRIX_SQL = """
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
			                least(count(recent_reels_avg_views), count(recent12_avg_engagement_rate)) AS baseline,
			                			                least(count(sponsored_signal_level), count(detected_product_categories), count(vlm_attributes)) AS vlm
			         FROM content_analyses),
			  -- 해석(파트 B) 채움율은 "해석 단계까지 온 행"만 분모로 삼는다(2026-09-03 2단계 분리).
			  -- 파트 A만 채워진 행(metric_timeliness='pending')은 아직 해석을 만들 차례가 아니라
			  -- 결손이 아니다 - 전체를 분모로 두면 파트 A가 쌓이는 만큼 상시 '부분'으로 보인다.
			  -- <> 가 아니라 IS DISTINCT FROM: 시점이 NULL인 레거시 기분석분을 빠뜨리면 안 된다.
			  anb AS (SELECT count(*) AS total,
			                 least(count(ai_content_summary), count(contents_pattern),
			                       count(ai_comment_insight)) AS copy3,
			                 count(comment_authenticity_grade) AS cauth
			          FROM content_analyses WHERE metric_timeliness IS DISTINCT FROM 'pending'),
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
			SELECT ord, element, source, filled, status FROM (
			  SELECT 1 AS ord, '계정 핸들·이름·프로필' AS element, 'accounts' AS source,
			         format('%s / %s', least(a.dname, a.pimg), a.total) AS filled,
			         CASE WHEN a.total > 0 AND least(a.dname, a.pimg) = a.total THEN '준비됨' ELSE '누락' END AS status
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
			         format('%s / %s', anb.copy3, anb.total),
			         CASE WHEN anb.copy3 = 0 THEN '없음' WHEN anb.copy3 < anb.total THEN '부분' ELSE '준비됨' END
			  FROM anb
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
			         format('%s / %s', anb.cauth, anb.total),
			         CASE WHEN anb.cauth = 0 THEN '없음' WHEN anb.cauth < anb.total THEN '부분' ELSE '준비됨' END
			  FROM anb
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
			) t ORDER BY ord
			""";

	// 구 산출물 — 07-12 초기화 전 스키마의 잔재로, 개편 스키마에는 없을 수 있다(프론트 미소비 — 구 /dashboard만 읽음).
	// 본 쿼리에 넣으면 테이블 부재 시 매트릭스 전체가 죽으므로 분리 조회한다.
	private static final String RANKING_SQL = """
			SELECT count(*) FROM content_ranking
			""";

	private static final String TILES_SQL = """
			SELECT (SELECT count(*) FROM contents)                                   AS contents,
			       (SELECT count(*) FROM accounts)                                   AS accounts,
			       (SELECT count(*) FROM content_analyses)                           AS analyses
			""";

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;

	public CoverageRepository(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
	}

	public CoverageSource source() {
		try {
			return raw.queryForObject(SOURCE_SQL,
					(rs, i) -> new CoverageSource(rs.getLong("accounts"), rs.getLong("contents")));
		} catch (DataAccessException e) {
			log.warn("수집 모수 조회 실패(raw DB), 타일 없이 렌더합니다: {}", e.getMessage());
			return null;
		}
	}

	public List<CoverageRow> matrix() {
		List<CoverageRow> rows;
		try {
			rows = analysis.query(MATRIX_SQL,
					(rs, i) -> CoverageRow.of(
							rs.getInt("ord"), rs.getString("element"), rs.getString("source"),
							rs.getString("filled"), rs.getString("status")));
		} catch (DataAccessException e) {
			log.warn("커버리지 매트릭스 조회 실패, 빈 목록으로 대체합니다: {}", e.getMessage());
			return List.of();
		}
		List<CoverageRow> combined = new ArrayList<>(rows);
		combined.add(rankingRow());
		return List.copyOf(combined);
	}

	private CoverageRow rankingRow() {
		try {
			Long total = analysis.queryForObject(RANKING_SQL, Long.class);
			return CoverageRow.of(28, "주간 랭킹 (프론트 미소비 — 구 대시보드 전용)", "content_ranking",
					"%s행".formatted(total),
					total == null || total == 0 ? "없음" : "옛 산출물 — 정리 대상");
		} catch (DataAccessException e) {
			return CoverageRow.of(28, "주간 랭킹 (프론트 미소비 — 구 대시보드 전용)", "content_ranking",
					"테이블 없음", "개편 스키마 밖 — 정리 대상");
		}
	}

	public CoverageTiles tiles() {
		try {
			return analysis.queryForObject(TILES_SQL,
					(rs, i) -> new CoverageTiles(
							rs.getLong("contents"), rs.getLong("accounts"), rs.getLong("analyses")));
		} catch (DataAccessException e) {
			log.warn("커버리지 타일 조회 실패, 빈 값으로 대체합니다: {}", e.getMessage());
			return null;
		}
	}
}
