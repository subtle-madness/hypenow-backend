package com.celfit.was.v1.content;

import com.celfit.was.v1.content.V1ContentReportRepository.CommentRow;
import com.celfit.was.v1.content.V1ContentReportRepository.ReelPointRow;
import com.celfit.was.v1.content.V1ContentReportRepository.ReportRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 미러 행 → ContentAiReport(스펙 6.3) 순수 변환 — 조립 규칙은 P1 계획서 매핑표가 정본. */
@Component
public class V1ContentReportAssembler {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final BigDecimal ZERO_RATE = new BigDecimal("0.00");
	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};
	private static final TypeReference<List<Map<String, String>>> OBJ_LIST = new TypeReference<>() {
	};

	private final ObjectMapper objectMapper;

	public V1ContentReportAssembler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public ContentAiReport toReport(ReportRow row, List<ReelPointRow> reels,
			Map<String, Long> categoryCounts, List<CommentRow> comments) {
		return new ContentAiReport(
				new ContentAiReport.Scope("recent-posts",
						row.recentContentsCount() == null ? 0L : row.recentContentsCount()),
				row.aiContentSummary(),
				comparison(row, reels),
				new ContentAiReport.CategoryContext(row.categoryLabel(), row.categoryTopPercentile(),
						row.categoryAvgViews(), row.categorySampleSize()),
				vlmAnalysis(row),
				commentAnalysis(row, categoryCounts),
				comments.stream().map(c -> new ContentAiReport.Comment(String.valueOf(c.id()),
						c.authorMasked(), c.body(), c.likeCount(), c.category())).toList());
	}

	private ContentAiReport.Comparison comparison(ReportRow row, List<ReelPointRow> reels) {
		var recentReels = reels.stream()
				.map(r -> new ContentAiReport.Comparison.Views.ReelPoint(
						r.shortCode(), r.views(), kstDate(r.postedAt()),
						Objects.equals(r.shortCode(), row.shortCode())))
				.toList();

		// 라이브 파생: 비NULL 릴스 조회수만 (v_analysis_baseline reels 규칙과 동일).
		// content_analyses의 고정 baseline/rank/count는 분석 시점 LLM 서술(narrative)의 입력값일 뿐
		// 여기서 다시 읽지 않는다 — 차트는 항상 최신 recentReels에서 라이브 계산.
		List<Long> reelViews = nonNullViews(reels);
		Long baseline = reelViews.isEmpty() ? null
				: Math.round(reelViews.stream().mapToLong(Long::longValue).average().orElse(0));
		Integer recentCount = reelViews.isEmpty() ? null : reelViews.size();
		Integer rankInRecent = rankInRecent(reels, row.shortCode());

		var views = new ContentAiReport.Comparison.Views(row.views(), baseline,
				multiple(row.views(), baseline), rankInRecent, recentCount, recentReels);
		var engagementRate = new ContentAiReport.Comparison.EngagementRate(
				engagementRateValue(row.views(), row.likes(), row.comments()),
				row.recent12AvgEngagementRate());
		var quality = new ContentAiReport.Comparison.EngagementQuality(
				new ContentAiReport.Comparison.EngagementQuality.Counts(row.likes(), row.recent12AvgLikeCount()),
				new ContentAiReport.Comparison.EngagementQuality.Counts(row.comments(), row.recent12AvgCommentCount()));
		return new ContentAiReport.Comparison(views, engagementRate, quality, row.contentsPattern());
	}

	/** 이 콘텐츠의 조회수 랭크(비NULL 릴스 중 내림차순, 경쟁 랭크). 본인 views가 NULL(피드 등)이면 null. */
	private Integer rankInRecent(List<ReelPointRow> reels, String shortCode) {
		Long self = reels.stream().filter(r -> Objects.equals(shortCode, r.shortCode()))
				.map(ReelPointRow::views).filter(Objects::nonNull).findFirst().orElse(null);
		if (self == null) {
			return null;
		}
		long higher = nonNullViews(reels).stream().filter(v -> v > self).count();
		return (int) (higher + 1);
	}

	/** 릴스 목록에서 조회수 NULL(미집계) 제외 — baseline·rank·count 계산 공통 재료. */
	private List<Long> nonNullViews(List<ReelPointRow> reels) {
		return reels.stream().map(ReelPointRow::views).filter(Objects::nonNull).toList();
	}

	private ContentAiReport.VlmAnalysis vlmAnalysis(ReportRow row) {
		List<ContentAiReport.VlmAnalysis.Brand> brands = objects(row.brandsJson()).stream()
				.map(m -> new ContentAiReport.VlmAnalysis.Brand(m.get("name"), m.get("evidence"))).toList();
		List<ContentAiReport.VlmAnalysis.Attribute> attributes = objects(row.attributesJson()).stream()
				.map(m -> new ContentAiReport.VlmAnalysis.Attribute(m.get("label"), m.get("value"))).toList();
		return new ContentAiReport.VlmAnalysis(brands,
				new ContentAiReport.VlmAnalysis.SponsoredSignal(row.sponsoredSignalLevel(),
						strings(row.reasonsJson())),
				row.adDisclosure(), strings(row.productCategoriesJson()), attributes);
	}

	private ContentAiReport.CommentAnalysis commentAnalysis(ReportRow row, Map<String, Long> counts) {
		long total = counts.values().stream().mapToLong(Long::longValue).sum();
		var signals = new ContentAiReport.CommentAnalysis.Signals(
				categoryRate(counts, "adAware", total), categoryRate(counts, "friendTag", total),
				new ContentAiReport.CommentAnalysis.Signals.Authenticity(
						row.commentAuthenticityGrade(), row.commentAuthenticityNote()));
		return new ContentAiReport.CommentAnalysis(distribution(counts), signals, row.aiCommentInsight());
	}

	/** value/baseline 소수 1자리 HALF_UP — 어느 한쪽 null 또는 baseline 0이면 null (피드 규칙). */
	BigDecimal multiple(Long value, Long baseline) {
		if (value == null || baseline == null || baseline == 0L) {
			return null;
		}
		return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(baseline), 1, RoundingMode.HALF_UP);
	}

	/** (likes+comments)/views×100 소수 2자리 HALF_UP — views null·0(피드)이면 null. */
	BigDecimal engagementRateValue(Long views, Long likes, Long comments) {
		if (views == null || views == 0L) {
			return null;
		}
		long engaged = (likes == null ? 0L : likes) + (comments == null ? 0L : comments);
		return BigDecimal.valueOf(engaged * 100L)
				.divide(BigDecimal.valueOf(views), 2, RoundingMode.HALF_UP);
	}

	/** 카테고리별 비율(전체 대비, 2자리) — 분류 0건이면 빈 배열. 정렬은 건수 내림차순. */
	List<ContentAiReport.CommentAnalysis.Slice> distribution(Map<String, Long> counts) {
		long total = counts.values().stream().mapToLong(Long::longValue).sum();
		if (total == 0L) {
			return List.of();
		}
		return counts.entrySet().stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed()
						.thenComparing(Map.Entry.comparingByKey()))
				.map(e -> new ContentAiReport.CommentAnalysis.Slice(e.getKey(), ratio(e.getValue(), total)))
				.toList();
	}

	/** 특정 분류의 비율 — 분류가 아예 0건이면 0.00 (스펙 시그널 규약). */
	private BigDecimal categoryRate(Map<String, Long> counts, String category, long total) {
		if (total == 0L) {
			return ZERO_RATE;
		}
		return ratio(counts.getOrDefault(category, 0L), total);
	}

	private BigDecimal ratio(long count, long total) {
		return BigDecimal.valueOf(count).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
	}

	/** KST 달력 날짜 "YYYY-MM-DD" (스펙 3.4). */
	private String kstDate(OffsetDateTime at) {
		return at == null ? null : at.atZoneSameInstant(KST).toLocalDate().toString();
	}

	/** jsonb 문자열 배열 → List. null(VLM 미실행)은 빈 배열 — 구조는 항상 유지. */
	private List<String> strings(String json) {
		if (json == null) {
			return List.of();
		}
		return objectMapper.readValue(json, STRING_LIST);
	}

	/** [{k:v}] 형태 jsonb → List<Map>. null은 빈 배열. */
	private List<Map<String, String>> objects(String json) {
		if (json == null) {
			return List.of();
		}
		return objectMapper.readValue(json, OBJ_LIST);
	}
}
