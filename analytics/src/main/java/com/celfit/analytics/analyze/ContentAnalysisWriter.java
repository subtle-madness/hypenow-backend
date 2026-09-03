package com.celfit.analytics.analyze;

import com.celfit.analytics.llm.ContentAttributes;
import com.celfit.analytics.llm.Synthesis;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** content_analyses 쓰기 단일 원천 — 일상 잡·백필 러너·해석 문구 갱신 잡이 공유한다. 컬럼 변경 시 이 한 곳만. */
final class ContentAnalysisWriter {

	private ContentAnalysisWriter() {}

	/**
	 * @param conflictIgnore true면 이미 분석된 행은 건너뛴다(백필 재실행 멱등 — 일상 잡은 false).
	 * @param metricTimeliness 지표 시점 마킹(V33) — 일상 잡(ContentAnalysisJob)·백필 러너
	 *        (GeminiBackfillRunner) 모두 제때 가드 충족 여부(timely)에 따라 timely/late_backfill을
	 *        직접 분기한다(07-20 개정: 늦크롤도 최근 N개 윈도우 안이면 late_backfill로 대상에 포함 —
	 *        더 이상 "백필=항상 late_backfill"이 아니다). 어휘는 V33 CHECK가 단일 원천.
	 */
	static void insert(JdbcTemplate analysis, ObjectMapper json, String shortCode, String model,
			Baseline b, ContentAttributes attrs, Synthesis s, boolean conflictIgnore,
			String metricTimeliness) {
		FactParams fp = factParams(json, attrs);
		analysis.update("""
				INSERT INTO content_analyses (short_code, model,
				  ai_content_summary, contents_pattern, ai_comment_insight,
				  recent_reels_avg_views, rank_in_recent_reels, recent_reels_count, recent_contents_count,
				  recent12_avg_engagement_rate, recent12_avg_like_count, recent12_avg_comment_count,
				  category_top_percentile, category_avg_views, category_sample_size,
				  detected_brands, sponsored_signal_level, sponsored_signal_reasons, ad_disclosure,
				  detected_product_categories, detected_products, vlm_attributes, main_category, sub_categories,
				  detected_distributors, ad_type,
				  comment_authenticity_grade, comment_authenticity_note, metric_timeliness, is_beauty,
				  synthesis_version, synthesized_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
				        ?::jsonb, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?,
				        ?, now())"""
				+ (conflictIgnore ? " ON CONFLICT (short_code) DO NOTHING" : ""),
				shortCode, model,
				s.aiContentSummary(), s.contentsPattern(), s.aiCommentInsight(),
				b.recentReelsAvgViews(), b.rankInRecentReels(), b.recentReelsCount(), b.recentContentsCount(),
				b.recent12AvgEngagementRate(), b.recent12AvgLikeCount(), b.recent12AvgCommentCount(),
				b.categoryTopPercentile(), b.categoryAvgViews(), b.categorySampleSize(),
				fp.detectedBrands(), fp.sponsoredSignalLevel(), fp.sponsoredSignalReasons(), fp.adDisclosure(),
				fp.detectedProductCategories(), fp.detectedProducts(), fp.vlmAttributes(), fp.mainCategory(),
				fp.subCategories(), fp.detectedDistributors(), fp.adType(),
				s.commentAuthenticityGrade(), s.commentAuthenticityNote(), metricTimeliness,
				fp.isBeauty(), Synthesis.VERSION);
	}

	/**
	 * 파트 A(사실)만 INSERT - 해석 5필드·기준선 10컬럼은 NULL로 두고 {@code metric_timeliness}를
	 * 신규 어휘 {@code 'pending'}으로 남긴다(2026-09-03 2단계 분리 설계 §3·§4-4).
	 *
	 * <p>기준선 스냅샷을 넣지 않는 이유: D+1 기준선은 미성숙 지표를 포함해 드로어 벤치마크에
	 * 하향 편향을 주고, 어차피 파트 B가 D+4 기준선으로 덮는다. was의
	 * {@code V1ContentReportAssembler.comparableMetric}은 timely 또는 NULL일 때만 비교 블록을
	 * 만들므로 'pending'이면 자동 억제된다.
	 *
	 * <p>{@code ON CONFLICT DO NOTHING} 고정 - 같은 배치가 두 번 수거되거나 파트 A 제출이 겹쳐도
	 * 이미 파트 B까지 채워진 행을 되돌리면 안 된다.
	 *
	 * @return INSERT된 행 수(0·1) - 0이면 이미 존재하는 행(ON CONFLICT DO NOTHING이 삼킴). 호출자가
	 *         예상 밖 빈도를 눈치챌 수 있게 반환한다(2026-09-03 리뷰 M8).
	 */
	static int insertFacts(JdbcTemplate analysis, ObjectMapper json, String shortCode, String model,
			ContentAttributes attrs) {
		FactParams fp = factParams(json, attrs);
		return analysis.update("""
				INSERT INTO content_analyses (short_code, model,
				  detected_brands, sponsored_signal_level, sponsored_signal_reasons, ad_disclosure,
				  detected_product_categories, detected_products, vlm_attributes, main_category,
				  sub_categories, detected_distributors, ad_type, is_beauty, metric_timeliness)
				VALUES (?, ?, ?::jsonb, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?,
				        ?::jsonb, ?::jsonb, ?, ?, 'pending')
				ON CONFLICT (short_code) DO NOTHING""",
				shortCode, model,
				fp.detectedBrands(), fp.sponsoredSignalLevel(), fp.sponsoredSignalReasons(), fp.adDisclosure(),
				fp.detectedProductCategories(), fp.detectedProducts(), fp.vlmAttributes(), fp.mainCategory(),
				fp.subCategories(), fp.detectedDistributors(), fp.adType(), fp.isBeauty());
	}

	/**
	 * 해석 문구만 갱신 — 사실 추출 컬럼(브랜드·카테고리·ad_type 등)은 손대지 않는다.
	 *
	 * <p>기준선 스냅샷도 함께 갱신한다: 문구가 인용하는 수치와 저장된 스냅샷이 갈리면
	 * 동결의 의미("LLM이 본 것 = LLM이 말한 것")가 깨지기 때문이다.
	 *
	 * <p>2026-09-03(2단계 분리): {@code metric_timeliness}도 SET한다. 파트 A가 만든 'pending'
	 * 행을 파트 B가 timely / late_backfill로 확정하는 지점이 여기다. 재생성 잡
	 * ({@code ContentSynthesisRefreshJob})은 저장된 값을 그대로 넘겨 동작이 불변이다.
	 * {@code WHERE ... AND metric_timeliness = 'pending'} 같은 조건은 걸지 않는다 -
	 * 재생성 잡이 이미 확정된 행에 같은 메서드를 쓰기 때문이다. 파트 B 최초 생성 경로(스윕·온라인
	 * 폴백)는 대신 아래 {@link #updateSynthesisPending}을 쓴다 - "이미 확정된 행"과 "이제 막
	 * 확정하는 행"의 요구가 서로 달라 조건 하나로 겸용하면 어느 한쪽이 틀린다.
	 *
	 * @param metricTimeliness 지표 시점 마킹(V33 어휘 + 09-03 'pending'). 파트 B 수거는
	 *        사이드카의 timely로, 재생성 잡은 저장된 기존 값으로 넘긴다.
	 * @return 갱신된 행 수 (0이면 그 사이 행이 사라진 것)
	 */
	static int updateSynthesis(JdbcTemplate analysis, String shortCode, String model,
			Baseline b, Synthesis s, String metricTimeliness) {
		return analysis.update("""
				UPDATE content_analyses SET
				  ai_content_summary = ?, contents_pattern = ?, ai_comment_insight = ?,
				  comment_authenticity_grade = ?, comment_authenticity_note = ?,
				  recent_reels_avg_views = ?, rank_in_recent_reels = ?, recent_reels_count = ?,
				  recent_contents_count = ?, recent12_avg_engagement_rate = ?,
				  recent12_avg_like_count = ?, recent12_avg_comment_count = ?,
				  category_top_percentile = ?, category_avg_views = ?, category_sample_size = ?,
				  model = ?, metric_timeliness = ?, synthesis_version = ?, synthesized_at = now()
				WHERE short_code = ?""",
				s.aiContentSummary(), s.contentsPattern(), s.aiCommentInsight(),
				s.commentAuthenticityGrade(), s.commentAuthenticityNote(),
				b.recentReelsAvgViews(), b.rankInRecentReels(), b.recentReelsCount(),
				b.recentContentsCount(), b.recent12AvgEngagementRate(),
				b.recent12AvgLikeCount(), b.recent12AvgCommentCount(),
				b.categoryTopPercentile(), b.categoryAvgViews(), b.categorySampleSize(),
				model, metricTimeliness, Synthesis.VERSION, shortCode);
	}

	/**
	 * {@link #updateSynthesis}와 SET 목록은 같되 {@code WHERE short_code = ? AND
	 * metric_timeliness = 'pending'}로 좁힌다(2026-09-03 리뷰) - 파트 B 최초 생성 경로(배치 수거
	 * {@code GeminiBatchLines.processSynthesisResultLine}·온라인
	 * {@code ContentAnalysisJob.synthesizeOne})가 쓴다. 막을 시나리오: split ON → 파트 B 배치
	 * 제출 → 운영자가 롤백하며 pending 행을 삭제 → 통합(unified) ANALYZE가 같은 short_code를
	 * 완결 행(non-pending)으로 재생성 → 그 뒤에 옛 파트 B 배치 결과가 도착해 방금 만든 완결 행을
	 * 덮어쓴다. pending 가드가 있으면 그 UPDATE는 0행이라 조용히 실패로 집계되고 완결 행은 그대로
	 * 보존된다.
	 *
	 * <p>재생성 잡({@code ContentSynthesisRefreshJob})은 이 메서드를 쓰지 않는다 - 그 잡의 대상은
	 * 이미 확정된(non-pending) 행이라 pending 가드를 걸면 자기 자신의 정상 갱신이 전부 0행이 된다.
	 *
	 * @return 갱신된 행 수 (0이면 그 사이 행이 사라졌거나, 이미 non-pending으로 확정된 행이라
	 *         대상이 아니었던 것 - 호출자가 warn으로 집계한다)
	 */
	static int updateSynthesisPending(JdbcTemplate analysis, String shortCode, String model,
			Baseline b, Synthesis s, String metricTimeliness) {
		return analysis.update("""
				UPDATE content_analyses SET
				  ai_content_summary = ?, contents_pattern = ?, ai_comment_insight = ?,
				  comment_authenticity_grade = ?, comment_authenticity_note = ?,
				  recent_reels_avg_views = ?, rank_in_recent_reels = ?, recent_reels_count = ?,
				  recent_contents_count = ?, recent12_avg_engagement_rate = ?,
				  recent12_avg_like_count = ?, recent12_avg_comment_count = ?,
				  category_top_percentile = ?, category_avg_views = ?, category_sample_size = ?,
				  model = ?, metric_timeliness = ?, synthesis_version = ?, synthesized_at = now()
				WHERE short_code = ? AND metric_timeliness = 'pending'""",
				s.aiContentSummary(), s.contentsPattern(), s.aiCommentInsight(),
				s.commentAuthenticityGrade(), s.commentAuthenticityNote(),
				b.recentReelsAvgViews(), b.rankInRecentReels(), b.recentReelsCount(),
				b.recentContentsCount(), b.recent12AvgEngagementRate(),
				b.recent12AvgLikeCount(), b.recent12AvgCommentCount(),
				b.categoryTopPercentile(), b.categoryAvgViews(), b.categorySampleSize(),
				model, metricTimeliness, Synthesis.VERSION, shortCode);
	}

	/**
	 * {@link ContentAttributes} → SQL 파라미터 11개 매핑 - {@link #insert}·{@link #insertFacts}가
	 * 공유한다(2026-09-03 리뷰). attrs가 null(캡션도 썸네일도 없어 속성 근거가 전무한 콘텐츠)이면
	 * 전부 null - 신규 사실 컬럼을 추가할 때 고칠 곳이 이 한 메서드로 좁혀진다.
	 */
	private record FactParams(String detectedBrands, String sponsoredSignalLevel, String sponsoredSignalReasons,
			String adDisclosure, String detectedProductCategories, String detectedProducts, String vlmAttributes,
			String mainCategory, String subCategories, String detectedDistributors, String adType,
			Boolean isBeauty) {}

	private static FactParams factParams(ObjectMapper json, ContentAttributes attrs) {
		if (attrs == null) {
			return new FactParams(null, null, null, null, null, null, null, null, null, null, null, null);
		}
		return new FactParams(
				toJson(json, attrs.detectedBrands()), attrs.sponsoredSignalLevel(),
				toJson(json, attrs.sponsoredSignalReasons()), attrs.adDisclosure(),
				toJson(json, attrs.detectedProductCategories()), toJson(json, attrs.detectedProducts()),
				toJson(json, attrs.vlmAttributes()), attrs.mainCategory(),
				toJson(json, attrs.subCategories()), toJson(json, attrs.detectedDistributors()),
				attrs.adType(), attrs.isBeauty());
	}

	private static String toJson(ObjectMapper json, Object value) {
		return value == null ? null : json.writeValueAsString(value);
	}
}
