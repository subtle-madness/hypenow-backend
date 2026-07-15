package com.celfit.was.influencer;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.AccountAnalysis;
import com.celfit.contract.analysis.AccountCategoryStat;
import com.celfit.contract.analysis.AccountContentPoint;
import com.celfit.contract.analysis.AccountSummary;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 계약 record 5종(AccountSummary·Account·AccountCategoryStat·AccountContentPoint·AccountAnalysis)
 * → 응답 블록 조립. 집합 연산 없음 — 전 지표는 C1이 저장한 값을, LLM 카피 7종은 C2 최신 이력 값을
 * 그대로 전달한다(분석 이력 없으면 카피 필드 null — additive). was 몫은 경과일·isActive·lastAdNote 문구·
 * 광고 strip 배열뿐(C1 스펙 §3 말미).
 */
@Component
public class InfluencerDetailAssembler {

	/** isActive 판정 경계(일) — 프론트 상수(C1 스펙 §5), was가 판정. */
	private static final long ACTIVE_THRESHOLD_DAYS = 14;

	private final Clock clock;

	public InfluencerDetailAssembler(Clock clock) {
		this.clock = clock;
	}

	public InfluencerDetailResponse toResponse(AccountSummary summary, Account account,
			List<AccountCategoryStat> categoryStats, List<AccountContentPoint> series,
			AccountAnalysis analysis) {
		return new InfluencerDetailResponse(
				toProfile(summary, account), toReport(summary, categoryStats, series, analysis));
	}

	private InfluencerDetailResponse.Profile toProfile(AccountSummary summary, Account account) {
		return new InfluencerDetailResponse.Profile(
				summary.handle(),
				account == null ? null : account.displayName(),
				account == null ? null : account.profileImageUrl(),
				summary.followers(),
				summary.followsCount(),
				summary.postsCount(),
				summary.biography());
	}

	private InfluencerDetailResponse.Report toReport(AccountSummary summary,
			List<AccountCategoryStat> categoryStats, List<AccountContentPoint> series,
			AccountAnalysis analysis) {
		OffsetDateTime now = OffsetDateTime.now(clock);
		return new InfluencerDetailResponse.Report(
				summary.postsCount(),
				summary.analyzedCount(),
				analysis == null ? null : analysis.tagline(),
				analysis == null ? null : analysis.summary(),
				toStats(summary),
				toTrend(summary, analysis),
				toChart(summary, series, analysis),
				toContentMix(categoryStats, analysis),
				toAds(summary, series, now, analysis),
				toActivity(summary, now, analysis));
	}

	private InfluencerDetailResponse.Report.Stats toStats(AccountSummary summary) {
		return new InfluencerDetailResponse.Report.Stats(
				summary.metric(), summary.avgViews(), summary.viewsPerFollower(),
				summary.avgErPct(), summary.avgLikes(), summary.avgComments());
	}

	private InfluencerDetailResponse.Report.Trend toTrend(AccountSummary summary, AccountAnalysis analysis) {
		return new InfluencerDetailResponse.Report.Trend(
				summary.trendDirection(), summary.trendChangePct(),
				summary.trendOlderAvg(), summary.trendNewerAvg(),
				analysis == null ? null : analysis.trendNote());
	}

	private InfluencerDetailResponse.Report.Chart toChart(AccountSummary summary,
			List<AccountContentPoint> series, AccountAnalysis analysis) {
		return new InfluencerDetailResponse.Report.Chart(summary.metric(),
				series.stream()
						.map(p -> new InfluencerDetailResponse.Report.Bar(
								p.shortCode(), p.postedAt(), p.contentType(),
								p.views(), p.likes(), p.comments(), p.sponsored()))
						.toList(),
				analysis == null ? null : analysis.chartNote());
	}

	private InfluencerDetailResponse.Report.ContentMix toContentMix(List<AccountCategoryStat> categoryStats,
			AccountAnalysis analysis) {
		return new InfluencerDetailResponse.Report.ContentMix(
				categoryStats.stream()
						.map(c -> new InfluencerDetailResponse.Report.Category(c.mainGroup(), c.contentCount()))
						.toList(),
				analysis == null ? null : analysis.traits());
	}

	private InfluencerDetailResponse.Report.Ads toAds(AccountSummary summary, List<AccountContentPoint> series,
			OffsetDateTime now, AccountAnalysis analysis) {
		return new InfluencerDetailResponse.Report.Ads(
				summary.sponsoredCount(),
				series.stream().map(AccountContentPoint::sponsored).toList(),
				summary.lastAdPostedAt(),
				lastAdNote(summary.lastAdPostedAt(), now),
				analysis == null ? null : analysis.adHeadline(),
				toComparison(summary));
	}

	/** organicAvg·adAvg 둘 다 있어야 존재 — 하나라도 null이면 블록 전체 null(프론트 `o.comparison?` 분기 대응). */
	private InfluencerDetailResponse.Report.Comparison toComparison(AccountSummary summary) {
		if (summary.organicAvg() == null || summary.adAvg() == null) {
			return null;
		}
		return new InfluencerDetailResponse.Report.Comparison(
				summary.metric(), summary.comparisonOrganicCount(), summary.comparisonAdCount(),
				summary.organicAvg(), summary.adAvg(), summary.adDropPct());
	}

	private InfluencerDetailResponse.Report.Activity toActivity(AccountSummary summary, OffsetDateTime now,
			AccountAnalysis analysis) {
		Long lastUploadDaysAgo = daysSince(summary.lastPostedAt(), now);
		boolean isActive = lastUploadDaysAgo != null && lastUploadDaysAgo < ACTIVE_THRESHOLD_DAYS;
		return new InfluencerDetailResponse.Report.Activity(
				summary.lastPostedAt(), lastUploadDaysAgo, isActive, summary.avgIntervalDays(),
				analysis == null ? null : analysis.paceNote());
	}

	/** lastAdPostedAt 없으면 null, 경과 0일이면 "마지막 광고 오늘", N일이면 "마지막 광고 N일 전". */
	private String lastAdNote(OffsetDateTime lastAdPostedAt, OffsetDateTime now) {
		Long daysAgo = daysSince(lastAdPostedAt, now);
		if (daysAgo == null) {
			return null;
		}
		return daysAgo == 0 ? "마지막 광고 오늘" : "마지막 광고 " + daysAgo + "일 전";
	}

	/**
	 * 경과일 = 24시간 단위 경과 수(캘린더 날짜 경계 아님) — D·H와 동일 시맨틱의 로컬 중복
	 * (PostDetailAssembler#daysSincePosted 참고, 패키지 분리로 의도적 재구현).
	 */
	private Long daysSince(OffsetDateTime moment, OffsetDateTime now) {
		if (moment == null) {
			return null;
		}
		return ChronoUnit.DAYS.between(moment, now);
	}
}
