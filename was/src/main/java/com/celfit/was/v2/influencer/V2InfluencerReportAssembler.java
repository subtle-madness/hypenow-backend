package com.celfit.was.v2.influencer;

import com.celfit.was.v1.influencer.EffectiveFollowers;
import com.celfit.was.v2.influencer.InfluencerAiReportV2.Activity;
import com.celfit.was.v2.influencer.InfluencerAiReportV2.Ads;
import com.celfit.was.v2.influencer.InfluencerAiReportV2.ContentMix;
import com.celfit.was.v2.influencer.InfluencerAiReportV2.MetricCell;
import com.celfit.was.v2.influencer.InfluencerAiReportV2.StatSet;
import com.celfit.was.v2.influencer.InfluencerAiReportV2.TrendPoint;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.BrandCollabRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.CategoryRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.CopyRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.SeriesRow;
import com.celfit.was.v2.influencer.V2InfluencerReportRepository.SummaryRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 미러 행 → InfluencerAiReportV2(스펙 6.22) 순수 변환. copy는 비-null 전제(컨트롤러가 미생성 404).
 * 성장세·유효 팔로워·광고 간격·헤드라인은 알고리즘 산출(LLM 아님) — v1 리포트(07-27)와 동일 결정.
 */
@Component
public class V2InfluencerReportAssembler {

	private static final int VIEWS_TREND_MAX = 8;
	private static final int ER_TREND_MAX = 12;
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};

	private final Clock clock;
	private final ObjectMapper objectMapper;

	public V2InfluencerReportAssembler(Clock clock, ObjectMapper objectMapper) {
		this.clock = clock;
		this.objectMapper = objectMapper;
	}

	public InfluencerAiReportV2 toReport(SummaryRow summary, CopyRow copy, List<SeriesRow> series,
			List<CategoryRow> categories, List<BrandCollabRow> brandCollabs) {
		OffsetDateTime now = OffsetDateTime.now(clock);
		List<SeriesRow> sponsored = series.stream()
				.filter(s -> Boolean.TRUE.equals(s.sponsored())).toList();
		return new InfluencerAiReportV2(
				copy.tagline(),
				summary.analyzedCount(),
				summary.postsCount(),
				EffectiveFollowers.estimate(summary.followers(), series.stream()
						.map(s -> new EffectiveFollowers.Post(s.views(), s.likes(), s.comments()))
						.toList()),
				new Activity(daysSince(summary.lastPostedAt(), now), summary.avgIntervalDays()),
				copy.perfSummary(),
				copy.contentSummary(),
				// 광고 0건이면 null(스펙 6.22) — analytics가 "협찬 없음" 문구를 저장해도 서빙하지 않는다
				sponsored.isEmpty() ? null : copy.adSummary(),
				overallSet(summary, series),
				sponsoredSet(sponsored, summary.followers()),
				viewsTrend(series),
				erTrend(series, summary.followers()),
				new ContentMix(categories.stream()
						.map(c -> new ContentMix.Category(c.label(), c.cnt())).toList(),
						traits(copy)),
				ads(sponsored, brandCollabs, now));
	}

	/** overall — 값은 summary(SQL 집계), 성장세는 series 전체. sampleCount는 analyzedCount(창 크기). */
	private StatSet overallSet(SummaryRow summary, List<SeriesRow> series) {
		return new StatSet(
				new MetricCell(toBigDecimal(summary.avgViews()),
						growthPct(mapToDouble(series, s -> s.views() == null ? null : s.views().doubleValue()))),
				new MetricCell(scale1(summary.avgErPct()),
						growthPct(mapToDouble(series, V2InfluencerReportAssembler::erProxy))),
				new MetricCell(toBigDecimal(summary.avgLikes()),
						growthPct(mapToDouble(series, s -> s.likes() == null ? null : s.likes().doubleValue()))),
				new MetricCell(toBigDecimal(summary.avgComments()),
						growthPct(mapToDouble(series, s -> s.comments() == null ? null : s.comments().doubleValue()))),
				scale1(summary.viewsPerFollower()),
				summary.analyzedCount());
	}

	/** sponsored — 광고 0건이면 세트 자체가 null. 값은 series 재계산(summary는 전체 집계라 못 씀). */
	private StatSet sponsoredSet(List<SeriesRow> sponsored, Long followers) {
		if (sponsored.isEmpty()) {
			return null;
		}
		BigDecimal avgViews = avgPositive(sponsored.stream().map(SeriesRow::views).toList());
		return new StatSet(
				new MetricCell(avgViews,
						growthPct(mapToDouble(sponsored, s -> s.views() == null ? null : s.views().doubleValue()))),
				new MetricCell(adEr(sponsored, followers),
						growthPct(mapToDouble(sponsored, V2InfluencerReportAssembler::erProxy))),
				new MetricCell(avg(sponsored.stream().map(SeriesRow::likes).toList()),
						growthPct(mapToDouble(sponsored, s -> s.likes() == null ? null : s.likes().doubleValue()))),
				new MetricCell(avg(sponsored.stream().map(SeriesRow::comments).toList()),
						growthPct(mapToDouble(sponsored, s -> s.comments() == null ? null : s.comments().doubleValue()))),
				viewsPerFollower(avgViews, followers),
				(long) sponsored.size());
	}

	/** viewsTrend — 조회수 공개(views>0, 피드는 항상 null이라 자연 제외)만 올린 순, 최신 8개.
	 *  2개 미만이면 빈 배열(화면 "추이 데이터 부족" 빈 상태). */
	private List<TrendPoint> viewsTrend(List<SeriesRow> series) {
		List<SeriesRow> open = series.stream()
				.filter(s -> s.views() != null && s.views() > 0).toList();
		if (open.size() < 2) {
			return List.of();
		}
		List<SeriesRow> latest = open.size() > VIEWS_TREND_MAX
				? open.subList(open.size() - VIEWS_TREND_MAX, open.size()) : open;
		return latest.stream()
				.map(s -> new TrendPoint(kstDate(s.postedAt()), BigDecimal.valueOf(s.views())))
				.toList();
	}

	/** erTrend — 최근 게시물 전체 올린 순 최신 12개(창 확장 대비 뒤에서 절단 — series는 ASC라
	 *  앞을 자르면 오래된 쪽이 남는 실수가 생기기 쉽다, viewsTrend와 동일한 절단 방향),
	 *  게시물당 (좋아요+댓글)×100/팔로워 소수 1. 팔로워 근거 없으면 빈 배열. 음수 센티널(likes -1)은 0 클램프. */
	private List<TrendPoint> erTrend(List<SeriesRow> series, Long followers) {
		if (followers == null || followers <= 0) {
			return List.of();
		}
		return series.stream().skip(Math.max(0, series.size() - ER_TREND_MAX)).map(s -> {
			long likes = s.likes() == null ? 0 : Math.max(s.likes(), 0);
			long comments = s.comments() == null ? 0 : Math.max(s.comments(), 0);
			BigDecimal er = BigDecimal.valueOf((likes + comments) * 100.0 / followers)
					.setScale(1, RoundingMode.HALF_UP);
			return new TrendPoint(kstDate(s.postedAt()), er);
		}).toList();
	}

	private Ads ads(List<SeriesRow> sponsored, List<BrandCollabRow> collabs, OffsetDateTime now) {
		OffsetDateTime lastAd = sponsored.stream().map(SeriesRow::postedAt)
				.max(Comparator.naturalOrder()).orElse(null);
		Long lastAdDaysAgo = daysSince(lastAd, now);
		Long adIntervalDays = adIntervalDays(sponsored);
		String topBrand = collabs.isEmpty() ? null : collabs.get(0).name();
		return new Ads((long) sponsored.size(), adIntervalDays, lastAdDaysAgo,
				headline(lastAdDaysAgo, adIntervalDays, topBrand),
				collabs.stream().map(this::brand).toList());
	}

	private Ads.Brand brand(BrandCollabRow r) {
		List<String> otherHandles = jsonStringList(r.othersJson());
		return new Ads.Brand(r.name(), r.cnt(),
				otherHandles.stream().map(h -> new Ads.Brand.OtherInfluencer(h, h)).toList(),
				jsonStringList(r.contentIdsJson()));
	}

	/** 스팬일수/(건수-1) 반올림 정수(스펙: 소수 없음). 광고 2건 미만·전부 같은 날(스팬 0)이면 null
	 *  — "평균 0일 간격" 오해 방지(v1 리포트와 동일 결정). */
	static Long adIntervalDays(List<SeriesRow> sponsored) {
		if (sponsored.size() < 2) {
			return null;
		}
		OffsetDateTime min = sponsored.stream().map(SeriesRow::postedAt)
				.min(Comparator.naturalOrder()).orElseThrow();
		OffsetDateTime max = sponsored.stream().map(SeriesRow::postedAt)
				.max(Comparator.naturalOrder()).orElseThrow();
		long spanDays = ChronoUnit.DAYS.between(min, max);
		if (spanDays == 0) {
			return null;
		}
		return Math.round((double) spanDays / (sponsored.size() - 1));
	}

	/** 광고 헤드라인 — 사실값 템플릿(LLM 아님). 조사 회피 명사형. 광고 이력 없으면 null. */
	static String headline(Long lastAdDaysAgo, Long adIntervalDays, String topBrand) {
		if (lastAdDaysAgo == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		sb.append(lastAdDaysAgo == 0 ? "오늘" : "최근 " + lastAdDaysAgo + "일 전");
		sb.append(topBrand != null ? " " + topBrand + " 협업" : " 광고 게시");
		if (adIntervalDays != null) {
			sb.append(" · 평균 ").append(adIntervalDays).append("일 간격으로 광고 진행");
		}
		return sb.toString();
	}

	/**
	 * 성장세: 올린 순 앞절반(floor(n/2)) vs 뒤절반, 각 절반에서 값>0만 평균 —
	 * 10_account_detail trend CTE와 같은 경계·필터. 근거 부족(절반 비었음)이면 null.
	 * 스펙 각주("최근 N개를 반으로 나눠 앞 구간 대비 뒤 구간 비교")와 동일 정의.
	 */
	static Integer growthPct(List<Double> valuesInOrder) {
		int n = valuesInOrder.size();
		if (n < 2) {
			return null;
		}
		double olderSum = 0, newerSum = 0;
		int olderN = 0, newerN = 0;
		for (int i = 0; i < n; i++) {
			double v = valuesInOrder.get(i) == null ? 0 : valuesInOrder.get(i);
			if (v <= 0) {
				continue;
			}
			if (i < n / 2) {
				olderSum += v;
				olderN++;
			} else {
				newerSum += v;
				newerN++;
			}
		}
		if (olderN == 0 || newerN == 0) {
			return null;
		}
		return (int) Math.round(((newerSum / newerN) / (olderSum / olderN) - 1) * 100);
	}

	/** er 대용값 = likes+comments(팔로워 상수이므로 증감률은 실제 ER 증감률과 동일). 둘 다 null이면 null. */
	private static Double erProxy(SeriesRow s) {
		if (s.likes() == null && s.comments() == null) {
			return null;
		}
		long likes = s.likes() == null ? 0 : s.likes();
		long comments = s.comments() == null ? 0 : s.comments();
		return (double) (likes + comments);
	}

	private static <T> List<Double> mapToDouble(List<T> rows, Function<T, Double> f) {
		return rows.stream().map(f).toList();
	}

	/** views>0인 표본만 평균(반올림). 표본 없으면 null(세트 내 조회수 공개 게시물 없음). */
	private static BigDecimal avgPositive(List<Long> values) {
		List<Long> positive = values.stream().filter(v -> v != null && v > 0).toList();
		if (positive.isEmpty()) {
			return null;
		}
		return BigDecimal.valueOf(Math.round(
				positive.stream().mapToLong(Long::longValue).average().orElseThrow()));
	}

	/** null 아닌 값 전부 평균(반올림). 표본 없으면 null. */
	private static BigDecimal avg(List<Long> values) {
		List<Long> nonNull = values.stream().filter(Objects::nonNull).toList();
		if (nonNull.isEmpty()) {
			return null;
		}
		return BigDecimal.valueOf(Math.round(
				nonNull.stream().mapToLong(Long::longValue).average().orElseThrow()));
	}

	/** 광고 er = avg(likes+comments)×100/followers, 소수 1(HALF_UP). followers 근거 없으면 null. */
	private static BigDecimal adEr(List<SeriesRow> sponsored, Long followers) {
		if (followers == null || followers <= 0 || sponsored.isEmpty()) {
			return null;
		}
		double sum = 0;
		for (SeriesRow s : sponsored) {
			sum += (s.likes() == null ? 0 : s.likes()) + (s.comments() == null ? 0 : s.comments());
		}
		return BigDecimal.valueOf(sum / sponsored.size() * 100 / followers)
				.setScale(1, RoundingMode.HALF_UP);
	}

	/** 평균 조회수 ÷ 팔로워 소수 1(스펙 StatSet.viewsPerFollower). 근거 없으면 null. */
	private static BigDecimal viewsPerFollower(BigDecimal avgViews, Long followers) {
		if (avgViews == null || followers == null || followers <= 0) {
			return null;
		}
		return avgViews.divide(BigDecimal.valueOf(followers), 1, RoundingMode.HALF_UP);
	}

	private static BigDecimal toBigDecimal(Long value) {
		return value == null ? null : BigDecimal.valueOf(value);
	}

	private static BigDecimal scale1(BigDecimal v) {
		return v == null ? null : v.setScale(1, RoundingMode.HALF_UP);
	}

	/** 경과일 = 24시간 단위 경과 수(캘린더 날짜 경계 아님) — 기존 표면과 동일 시맨틱. */
	private Long daysSince(OffsetDateTime moment, OffsetDateTime now) {
		return moment == null ? null : ChronoUnit.DAYS.between(moment, now);
	}

	/** KST 달력 날짜 "YYYY-MM-DD"(스펙 3.4). */
	private String kstDate(OffsetDateTime at) {
		return at == null ? null : at.atZoneSameInstant(KST).toLocalDate().toString();
	}

	private List<String> traits(CopyRow copy) {
		return copy.traitsJson() == null ? List.of()
				: objectMapper.readValue(copy.traitsJson(), STRING_LIST);
	}

	private List<String> jsonStringList(String json) {
		return json == null ? List.of() : objectMapper.readValue(json, STRING_LIST);
	}
}
