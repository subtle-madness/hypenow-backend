package com.celfit.analytics.analyze;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.ContentAttributes;
import com.celfit.analytics.llm.ContentInsightPort;
import com.celfit.analytics.llm.ContentToAnalyze;
import com.celfit.analytics.llm.Synthesis;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 콘텐츠 분석 배치 (스펙 §6). 분석 시점 고정·불변 — INSERT만, 재분석 없음.
 * 대상: 미분석 AND (댓글 없음 OR 분류 완료) AND 게시 후 N일 경과(기본 3 — B3 숙성 가드)
 * AND (제때 크롤 가드 OR 계정별 최근 N개 윈도우 — 07-20 개정: 늦크롤 백필 재도입, V33 timely/late_backfill 분기).
 * 속성 분석은 캡션 주·썸네일 보조 (2026-07-14 캡션 분류 스펙) — 썸네일 만료여도 캡션으로 5종 산출.
 * 콘텐츠 단위 실패 격리: 한 건 실패는 로그 후 계속 (B2 리뷰 반영).
 */
public class ContentAnalysisJob {

	private static final Logger log = LoggerFactory.getLogger(ContentAnalysisJob.class);

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final ContentInsightPort insight; // ②속성+③종합 통합 1콜 (07-18 확정)
	private final AnalyticsSettings settings;
	private final boolean thumbnailEnabled; // 썸네일 첨부 게이트 — off여도 캡션 기반 속성은 산출
	private final Predicate<String> thumbnailAlive;
	private final ProgressReporter reporter;
	private final ObjectMapper json = new ObjectMapper();

	public ContentAnalysisJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			ContentInsightPort insight, AnalyticsSettings settings,
			boolean thumbnailEnabled, Predicate<String> thumbnailAlive, ProgressReporter reporter) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.insight = insight;
		this.settings = settings;
		this.thumbnailEnabled = thumbnailEnabled;
		this.thumbnailAlive = thumbnailAlive;
		this.reporter = reporter;
	}

	/**
	 * @return 잡 실행 결과 (처리·실패 건수, 일 한도 이월 여부)
	 *
	 * <p>대상은 양쪽 DB 교집합: 기준선은 "최근 N개" 비교 지표라 윈도우 밖 콘텐츠는
	 * 분석 대상이 아니다 (기준선 정의 불가). 미러 전체(contents)에서 기준선 뷰에 없는
	 * 콘텐츠를 상한 적용 전에 걸러내지 않으면 매 실행 예외→skip으로 배치 상한 슬롯을
	 * 영구 잠식한다 (B2의 classified HashSet 패턴과 동일한 자바 측 필터).
	 */
	public JobResult run() {
		// 최신 수집순: 썸네일 서명 URL(만료 ~4일)이 살아있을 때 VLM을 시도하기 위한 정렬 (B3 VLM 잔여분).
		// 기준선을 여기서 통째로 로드한다 — 뷰 평가가 운영 실측 분 단위(07-19, 27k 기준 4.5분)라
		// 건당 WHERE 조회를 반복하면 배치가 뷰 스캔에 잠긴다. 1회 평가 후 메모리 맵 조회로 대체.
		Map<String, Baseline> withBaseline = new LinkedHashMap<>();
		raw.query("""
				SELECT short_code, recent_reels_avg_views, rank_in_recent_reels, recent_reels_count,
				       recent_contents_count, recent12_avg_engagement_rate,
				       recent12_avg_like_count, recent12_avg_comment_count,
				       category_top_percentile, category_avg_views, category_sample_size
				FROM analytics.v_analysis_baseline ORDER BY captured_at DESC""",
				rs -> {
					// PG 타입이 numeric·bigint·smallint로 섞여 있어 전부 BigDecimal로 읽어 변환 (기존 관용구)
					withBaseline.put(rs.getString(1), new Baseline(
							longOf(rs.getBigDecimal(2)), intOf(rs.getBigDecimal(3)), intOf(rs.getBigDecimal(4)),
							intOf(rs.getBigDecimal(5)), rs.getBigDecimal(6),
							longOf(rs.getBigDecimal(7)), longOf(rs.getBigDecimal(8)),
							intOf(rs.getBigDecimal(9)), longOf(rs.getBigDecimal(10)), longOf(rs.getBigDecimal(11))));
				});
		// 숙성 가드: 게시 후 N일(기본 3) 경과분만 — 불변 테이블이라 게시 직후 분석되면 영구 고정 (07-14 확정).
		// 제때 크롤 가드(07-19 정정, 판정식은 07-20에도 보존): 고정 지표가 성숙(+pin일) 스냅샷이면서
		// +(pin+slack)일 안에 잡힌 것만 제때. raw 04 뷰는 이후 KST 캘린더일 기준으로 재정정됐지만
		// (커밋 eefb299·7067dd5) 그 계산엔 content_snapshot_cache가 필요하고 이 분석 DB 미러 contents엔
		// 없어 동일하게 옮길 수 없다 — 이 잡은 07-19 간격 기반 판정식을 그대로 유지한다(차이만 기록).
		// 자격 OR 확장(07-20 PO 결정, 스펙 docs/superpowers/specs/2026-07-20-vertex-migration-recent12-backfill-design.md):
		// "백필 MVP 제외"(07-19)를 번복 — 제때 가드를 못 채워도 계정별 최근 N개(recentWindow, 01 뷰와
		// 공유하는 recent-window 키) 윈도우 안이면 분석 대상에 포함한다(늦크롤 백필). 대상별 timely 여부를
		// 함께 읽어 V33 metric_timeliness 마킹(timely/late_backfill) 분기에 쓴다(analyzeOne에서 적용).
		// posted_at·metric_captured_at NULL은 제때 가드 부등식에서 자연 제외(미상이면 판정 불가) —
		// COALESCE로 timely=false 처리하되, posted_at이 살아 있으면 윈도우 경로로는 대상이 될 수 있다.
		// recency 타이브레이크는 01 뷰(content_id DESC)와 다르다 — 미러엔 raw content_id가 없어
		// posted_at DESC, short_code DESC로 대체(동시각 순서 미세 차이 가능, 실질 영향 없음).
		// 창 닫힘 게이트(최종 통합 리뷰 I-1): 윈도우 분기에만 posted_at + (pin+slack)일 <= now() 를
		// 추가로 건다 — 제때창이 아직 열려 있는 콘텐츠(숙성은 지났지만 pin+slack 미경과)를 윈도우
		// 경로로 조기 분석하면, 나중에 진짜 timely 스냅샷이 들어와도 content_analyses가 불변이라
		// late_backfill로 영구 오분류된다. timely 분기는 게이트가 필요 없다 — timely 술어 자체가
		// 이미 창 안에서 성숙 스냅샷이 잡혔음을 의미하기 때문. 04 뷰가 "제때창이 완전히 지난 날만"
		// 후보로 올리는 성숙 철학과 이 게이트로 정렬된다.
		int pinDays = settings.metricPinDays();
		int slackDays = settings.analyzeTimelySlackDays();
		// timely 술어는 base CTE에서 1회만 평가하고(중복 계산·중복 파라미터 제거 — 리뷰 반영),
		// 바깥 WHERE·SELECT 양쪽에서 그 결과 컬럼을 그대로 재사용한다. ranked는 계정별 전체
		// contents(가드와 무관) 기준 최근 N개 순위 — base의 다른 가드와 별개로 윈도우만 판단.
		Map<String, Boolean> eligible = new HashMap<>();
		analysis.query("""
				WITH base AS (
				  SELECT c.short_code,
				         COALESCE(c.metric_captured_at >= c.posted_at + make_interval(days => ?)
				              AND c.metric_captured_at < c.posted_at + make_interval(days => ?), false) AS timely
				  FROM contents c
				  WHERE NOT EXISTS (SELECT 1 FROM content_analyses a WHERE a.short_code = c.short_code)
				    AND (NOT EXISTS (SELECT 1 FROM content_comments m WHERE m.short_code = c.short_code)
				         OR EXISTS (SELECT 1 FROM comment_classifications k WHERE k.short_code = c.short_code))
				    AND c.posted_at <= now() - make_interval(days => ?)
				),
				ranked AS (
				  SELECT short_code, posted_at,
				         row_number() OVER (PARTITION BY account_handle
				             ORDER BY posted_at DESC, short_code DESC) AS rn
				  FROM contents
				)
				SELECT short_code, timely
				FROM base
				WHERE timely OR short_code IN (
				  SELECT short_code FROM ranked
				  WHERE rn <= ? AND posted_at <= now() - make_interval(days => ?)
				)""",
				rs -> {
					eligible.put(rs.getString(1), rs.getBoolean(2));
				},
				pinDays, pinDays + slackDays,
				settings.analyzeMaturityDays(),
				settings.recentWindow(), pinDays + slackDays);
		List<String> targets = withBaseline.keySet().stream()
				.filter(eligible::containsKey)
				.limit(settings.analyzeBatchLimit())
				.toList();
		String model = settings.activeLlmModel();
		int processed = 0;
		int failed = 0;
		boolean carriedOver = false;
		reporter.report(0, 0, targets.size());
		for (String shortCode : targets) {
			try {
				analyzeOne(shortCode, model, withBaseline.get(shortCode), eligible.get(shortCode));
				processed++;
			} catch (com.celfit.analytics.llm.LlmQuotaExhaustedException e) {
				// 일 한도 소진 — 에러가 아닌 이월: 남은 대상은 다음 실행에서 자연 재대상 (07-18 확정)
				log.warn("LLM 일 한도 소진 — 배치 중단, 잔여 {}건 이월", targets.size() - processed - failed);
				carriedOver = true;
				break;
			} catch (Exception e) {
				failed++;
				log.error("analysis failed for {} — 다음 실행에서 재대상", shortCode, e);
			}
			reporter.report(processed, failed, targets.size());
		}
		log.info("analysis complete ({} contents, {} failed)", processed, failed);
		return new JobResult(processed, failed, carriedOver);
	}

	private void analyzeOne(String shortCode, String model, Baseline b, boolean timely) {
		Map<String, Object> content = analysis.queryForMap("""
				SELECT account_handle, caption, content_type, thumbnail_url, views, likes, comments
				FROM contents WHERE short_code = ?""", shortCode);
		Map<String, Long> categoryCounts = new LinkedHashMap<>();
		analysis.query("""
				SELECT ai_category, count(*) AS cnt FROM comment_classifications
				WHERE short_code = ? GROUP BY ai_category""",
				rs -> {
					categoryCounts.put(rs.getString(1), rs.getLong(2));
				}, shortCode);
		// 캡션 주·썸네일 보조: 썸네일은 게이트 on + 프리체크 생존일 때만 첨부, 만료·off여도 캡션으로 5종 산출.
		// 통합 1콜(속성+종합 — 07-18 확정) 예외(일시 장애)는 기존대로 콘텐츠 실패 → 다음 실행 재대상.
		String caption = (String) content.get("caption");
		String thumbnailUrl = (String) content.get("thumbnail_url");
		boolean attachThumbnail = thumbnailEnabled && thumbnailUrl != null && thumbnailAlive.test(thumbnailUrl);
		if (thumbnailEnabled && thumbnailUrl != null && !attachThumbnail) {
			log.info("썸네일 만료/접근 불가 — 캡션만으로 속성 분석: {}", shortCode);
		}
		boolean hasCaption = caption != null && !caption.isBlank();
		Map<String, Object> baselineForPrompt = new LinkedHashMap<>();
		baselineForPrompt.put("recent_reels_avg_views", b.recentReelsAvgViews());
		baselineForPrompt.put("rank_in_recent_reels", b.rankInRecentReels());
		baselineForPrompt.put("recent_contents_count", b.recentContentsCount());
		baselineForPrompt.put("recent12_avg_engagement_rate", b.recent12AvgEngagementRate());
		baselineForPrompt.put("recent12_avg_like_count", b.recent12AvgLikeCount());
		baselineForPrompt.put("recent12_avg_comment_count", b.recent12AvgCommentCount());
		baselineForPrompt.put("category_top_percentile", b.categoryTopPercentile());
		ContentInsightPort.ContentInsight result = insight.analyze(new ContentToAnalyze(shortCode,
				(String) content.get("account_handle"), caption,
				(String) content.get("content_type"), (Long) content.get("views"),
				(Long) content.get("likes"), (Long) content.get("comments"),
				baselineForPrompt, categoryCounts), attachThumbnail ? thumbnailUrl : null);
		// 캡션도 썸네일도 없으면 속성 근거 입력이 없다 — 통합 콜이 돌려줘도 폐기하고 속성 컬럼 NULL 유지.
		ContentAttributes attrs = hasCaption || attachThumbnail ? result.attributes() : null;
		Synthesis s = result.synthesis();
		// content_analyses는 불변(INSERT만)이라 빈 결과가 저장되면 영구 고정 + 재분석 대상에서도 제외된다.
		// 저장 전에 실패 처리해 콘텐츠 단위 try/catch가 skip → 다음 실행에서 재대상되게 한다.
		if (s.aiContentSummary() == null || s.aiContentSummary().isBlank()) {
			throw new IllegalStateException("종합 텍스트가 비어 있음: " + shortCode);
		}
		// 뷰티 콘텐츠인데 복구 후에도 대분류를 못 얻으면 행을 남기지 않는다 — 저장되면 NOT EXISTS로
		// 영영 재분석 제외되므로, 실패 격리(skip)로 다음 실행에 재대상화(self-heal). 비뷰티(is_beauty=false)는
		// 정상 저장돼 루프에 안 빠진다. (설계 2026-07-20 §3-3)
		if (attrs != null && Boolean.TRUE.equals(attrs.isBeauty()) && attrs.mainCategory() == null) {
			throw new IllegalStateException("뷰티 콘텐츠인데 대분류 미분류 — 재대상: " + shortCode);
		}
		// V33 마킹 분기(07-20 개정): 제때 가드를 충족하면 timely, 윈도우 경로로만 들어온 늦크롤은 late_backfill.
		ContentAnalysisWriter.insert(analysis, json, shortCode, model, b, attrs, s, false,
				timely ? "timely" : "late_backfill");
	}

	private static Long longOf(java.math.BigDecimal v) {
		return v == null ? null : v.longValueExact();
	}

	private static Integer intOf(java.math.BigDecimal v) {
		return v == null ? null : v.intValueExact();
	}
}
