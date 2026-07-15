package com.celfit.was.v1.influencer;

import com.celfit.was.v1.influencer.V1InfluencerReportRepository.BrandRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.CategoryRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.CopyRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.SeriesRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.SummaryRow;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 미러 행 → InfluencerAiReport(스펙 6.5) 순수 변환 — 조립 규칙은 P1 계획서 매핑표가 정본.
 * was 몫은 lastAdNote 문구·경과일·isActive·strip 추출뿐(집합 연산은 전부 SQL/분석층).
 * 주의: 기존 E 표면(InfluencerDetailAssembler)과 문구·경계가 다르다 —
 * lastAdNote는 주 단위 문구, isActive는 14일 포함(<=). 스펙 6.5가 정본.
 */
@Component
public class V1InfluencerReportAssembler {

	/** isActive 판정 경계(일) — 14일까지 활동 중(스펙 6.5, 경계 포함). */
	private static final long ACTIVE_THRESHOLD_DAYS = 14;
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};

	private final Clock clock;
	private final ObjectMapper objectMapper;

	public V1InfluencerReportAssembler(Clock clock, ObjectMapper objectMapper) {
		this.clock = clock;
		this.objectMapper = objectMapper;
	}

	/** copy는 nullable(account_analyses 미생성) — 카피 필드만 null, 블록 구조는 유지. */
	public InfluencerAiReport toReport(SummaryRow summary, CopyRow copy, List<SeriesRow> series,
			List<CategoryRow> categories, List<BrandRow> brands) {
		OffsetDateTime now = OffsetDateTime.now(clock);
		return new InfluencerAiReport(
				copy == null ? null : copy.tagline(),
				summary.analyzedCount(),
				summary.postsCount(),
				copy == null ? null : copy.summary(),
				toStats(summary),
				new InfluencerAiReport.Trend(summary.trendDirection(), copy == null ? null : copy.trendNote()),
				toChart(summary, copy, series),
				toContentMix(copy, categories),
				toAds(summary, copy, series, brands, now),
				toActivity(summary, copy, now));
	}

	private InfluencerAiReport.Stats toStats(SummaryRow summary) {
		return new InfluencerAiReport.Stats(summary.metric(), summary.avgViews(),
				summary.viewsPerFollower(), summary.avgErPct(), summary.avgLikes(), summary.avgComments());
	}

	private InfluencerAiReport.Chart toChart(SummaryRow summary, CopyRow copy, List<SeriesRow> series) {
		return new InfluencerAiReport.Chart(summary.metric(), copy == null ? null : copy.chartNote(),
				series.stream()
						.map(p -> new InfluencerAiReport.Chart.Bar(p.views(), p.likes(), p.comments(),
								kstDate(p.postedAt()), p.sponsored(), p.contentType()))
						.toList());
	}

	private InfluencerAiReport.ContentMix toContentMix(CopyRow copy, List<CategoryRow> categories) {
		return new InfluencerAiReport.ContentMix(
				categories.stream()
						.map(c -> new InfluencerAiReport.ContentMix.Category(c.label(), c.cnt()))
						.toList(),
				traits(copy));
	}

	private InfluencerAiReport.Ads toAds(SummaryRow summary, CopyRow copy, List<SeriesRow> series,
			List<BrandRow> brands, OffsetDateTime now) {
		return new InfluencerAiReport.Ads(
				summary.sponsoredCount(),
				series.stream().map(SeriesRow::sponsored).toList(),
				lastAdNote(summary.lastAdPostedAt(), now),
				toComparison(summary),
				copy == null ? null : copy.adHeadline(),
				brands.stream().map(b -> new InfluencerAiReport.Ads.Brand(b.name(), b.cnt())).toList());
	}

	/** organicAvg·adAvg 둘 다 있어야 존재 — 하나라도 null이면 블록 전체 null(프론트 comparison? 분기 대응). */
	private InfluencerAiReport.Ads.Comparison toComparison(SummaryRow summary) {
		if (summary.organicAvg() == null || summary.adAvg() == null) {
			return null;
		}
		return new InfluencerAiReport.Ads.Comparison(summary.metric(),
				summary.comparisonOrganicCount(), summary.organicAvg(),
				summary.comparisonAdCount(), summary.adAvg(), summary.adDropPct());
	}

	private InfluencerAiReport.Activity toActivity(SummaryRow summary, CopyRow copy, OffsetDateTime now) {
		Long lastUploadDaysAgo = daysSince(summary.lastPostedAt(), now);
		boolean isActive = lastUploadDaysAgo != null && lastUploadDaysAgo <= ACTIVE_THRESHOLD_DAYS;
		return new InfluencerAiReport.Activity(lastUploadDaysAgo, isActive,
				summary.avgIntervalDays(), copy == null ? null : copy.paceNote());
	}

	/** 광고 이력 없으면 null, 경과 7일 미만이면 "이번 주 광고", 그 외 "마지막 광고 N주 전"(N=경과일/7 내림, 최소 1). */
	String lastAdNote(OffsetDateTime lastAdPostedAt, OffsetDateTime now) {
		Long daysAgo = daysSince(lastAdPostedAt, now);
		if (daysAgo == null) {
			return null;
		}
		if (daysAgo < 7) {
			return "이번 주 광고";
		}
		return "마지막 광고 " + Math.max(1, daysAgo / 7) + "주 전";
	}

	/** 경과일 = 24시간 단위 경과 수(캘린더 날짜 경계 아님) — 기존 E 표면과 동일 시맨틱. */
	private Long daysSince(OffsetDateTime moment, OffsetDateTime now) {
		if (moment == null) {
			return null;
		}
		return ChronoUnit.DAYS.between(moment, now);
	}

	/** KST 달력 날짜 "YYYY-MM-DD" (스펙 3.4). */
	private String kstDate(OffsetDateTime at) {
		return at == null ? null : at.atZoneSameInstant(KST).toLocalDate().toString();
	}

	/** traits jsonb 문자열 배열 → List. 카피 없음·jsonb null 모두 빈 배열 — 구조는 항상 유지. */
	private List<String> traits(CopyRow copy) {
		if (copy == null || copy.traitsJson() == null) {
			return List.of();
		}
		return objectMapper.readValue(copy.traitsJson(), STRING_LIST);
	}
}
