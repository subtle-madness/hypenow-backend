package com.celfit.was.v1.influencer;

import com.celfit.was.v1.influencer.InfluencerAiReport.Stats.Stat;
import com.celfit.was.v1.influencer.InfluencerAiReport.Stats.StatRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.BrandRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.CategoryRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.CopyRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.PeerStatsRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.ProductRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.SeriesRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.SummaryRow;
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
 * 미러 행 → InfluencerAiReport(스펙 6.5 v2, 07-27 개편) 순수 변환.
 * was 몫은 lastAdNote 문구·경과일·isActive·strip 추출·성장세/유효 팔로워/헤드라인 알고리즘 산출뿐
 * (LLM 카피 배치·집합 연산은 각각 analytics/SQL 몫). 카피(copy)는 nullable — 카피 필드만 null,
 * 블록 구조는 유지한다. peer(PeerStatsRow)도 nullable — 피어 표본 없으면 topPct·유효 팔로워는 null.
 */
@Component
public class V1InfluencerReportAssembler {

	/** isActive 판정 경계(일) — 14일까지 활동 중(스펙 6.5, 경계 포함). */
	private static final long ACTIVE_THRESHOLD_DAYS = 14;
	/** 피어 표본 최소 크기 — 미만이면 topPct 전부 숨김(퍼센타일 신뢰 불가). */
	private static final long MIN_PEER_SIZE = 3;
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};

	private final Clock clock;
	private final ObjectMapper objectMapper;

	public V1InfluencerReportAssembler(Clock clock, ObjectMapper objectMapper) {
		this.clock = clock;
		this.objectMapper = objectMapper;
	}

	/** copy·peer는 nullable — copy 없으면 카피 필드만 null, peer 없으면 topPct·유효 팔로워만 null.
	 *  블록 구조는 항상 유지된다. */
	public InfluencerAiReport toReport(SummaryRow summary, CopyRow copy, List<SeriesRow> series,
			List<CategoryRow> categories, List<BrandRow> brands, List<ProductRow> products,
			PeerStatsRow peer) {
		OffsetDateTime now = OffsetDateTime.now(clock);
		return new InfluencerAiReport(
				copy == null ? null : copy.tagline(),
				summary.analyzedCount(),
				summary.postsCount(),
				effectiveFollowers(summary.followers(), summary.avgErPct(), peer),
				effectiveFollowersPct(summary.followers(), summary.avgErPct(), peer),
				toStats(summary, copy, series, peer),
				toChart(summary, series),
				toContentMix(copy, categories),
				toAds(summary, copy, series, brands, products, now),
				toActivity(summary, now));
	}

	private InfluencerAiReport.Stats toStats(SummaryRow summary, CopyRow copy, List<SeriesRow> series,
			PeerStatsRow peer) {
		return new InfluencerAiReport.Stats(summary.metric(), summary.viewsPerFollower(),
				copy == null ? null : copy.perfSummary(),
				overallStatRow(summary, series, peer),
				adStatRow(series, summary.followers(), peer));
	}

	/** 전체 행 — value는 summary(이미 SQL이 평균낸 값), growthPct는 series 전체(올린 순), topPct는 피어. */
	private StatRow overallStatRow(SummaryRow summary, List<SeriesRow> series, PeerStatsRow peer) {
		return new StatRow(
				new Stat(toBigDecimal(summary.avgViews()),
						growthPct(mapToDouble(series, s -> s.views() == null ? null : s.views().doubleValue())),
						topPct(peer, peer == null ? null : peer.topPctViews())),
				new Stat(summary.avgErPct(),
						growthPct(mapToDouble(series, V1InfluencerReportAssembler::erProxy)),
						topPct(peer, peer == null ? null : peer.topPctEr())),
				new Stat(toBigDecimal(summary.avgLikes()),
						growthPct(mapToDouble(series, s -> s.likes() == null ? null : s.likes().doubleValue())),
						topPct(peer, peer == null ? null : peer.topPctLikes())),
				new Stat(toBigDecimal(summary.avgComments()),
						growthPct(mapToDouble(series, s -> s.comments() == null ? null : s.comments().doubleValue())),
						topPct(peer, peer == null ? null : peer.topPctComments())));
	}

	/** 광고 행 — sponsored 0건이면 null. 값은 series에서 재계산(summary는 전체 집계라 못 씀), topPct는 피어(ad_*). */
	private StatRow adStatRow(List<SeriesRow> series, Long followers, PeerStatsRow peer) {
		List<SeriesRow> sponsored = series.stream().filter(s -> Boolean.TRUE.equals(s.sponsored())).toList();
		if (sponsored.isEmpty()) {
			return null;
		}
		return new StatRow(
				new Stat(avgPositive(sponsored.stream().map(SeriesRow::views).toList()),
						growthPct(mapToDouble(sponsored, s -> s.views() == null ? null : s.views().doubleValue())),
						topPct(peer, peer == null ? null : peer.topPctAdViews())),
				new Stat(adEr(sponsored, followers),
						growthPct(mapToDouble(sponsored, V1InfluencerReportAssembler::erProxy)),
						topPct(peer, peer == null ? null : peer.topPctAdEr())),
				new Stat(avg(sponsored.stream().map(SeriesRow::likes).toList()),
						growthPct(mapToDouble(sponsored, s -> s.likes() == null ? null : s.likes().doubleValue())),
						topPct(peer, peer == null ? null : peer.topPctAdLikes())),
				new Stat(avg(sponsored.stream().map(SeriesRow::comments).toList()),
						growthPct(mapToDouble(sponsored, s -> s.comments() == null ? null : s.comments().doubleValue())),
						topPct(peer, peer == null ? null : peer.topPctAdComments())));
	}

	/** er 대용값 = likes+comments(팔로워 상수이므로 증감률은 실제 ER 증감률과 동일) — 둘 다 null이면 null. */
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

	/** topPct 규칙: 피어 없거나 표본 3 미만이면 전부 숨김(null). */
	private static Integer topPct(PeerStatsRow peer, Integer value) {
		if (peer == null || peer.peerSize() < MIN_PEER_SIZE) {
			return null;
		}
		return value;
	}

	/**
	 * 성장세: 올린 순 앞절반(floor(n/2)) vs 뒤절반, 각 절반에서 값>0만 평균 —
	 * 10_account_detail trend CTE와 같은 경계·필터. 근거 부족(절반 비었거나 older 0)이면 null.
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
		// olderN>0이면 olderSum도 반드시 >0(양수만 누적하므로) — olderSum==0 분기는 도달 불가라 생략.
		if (olderN == 0 || newerN == 0) {
			return null;
		}
		return (int) Math.round(((newerSum / newerN) / (olderSum / olderN) - 1) * 100);
	}

	/** views>0인 표본만 평균(반올림). 표본 없으면 null. */
	private static BigDecimal avgPositive(List<Long> values) {
		List<Long> positive = values.stream().filter(v -> v != null && v > 0).toList();
		if (positive.isEmpty()) {
			return null;
		}
		double avg = positive.stream().mapToLong(Long::longValue).average().orElseThrow();
		return BigDecimal.valueOf(Math.round(avg));
	}

	/** null이 아닌 값 전부 평균(반올림). 표본 없으면 null. */
	private static BigDecimal avg(List<Long> values) {
		List<Long> nonNull = values.stream().filter(Objects::nonNull).toList();
		if (nonNull.isEmpty()) {
			return null;
		}
		double avg = nonNull.stream().mapToLong(Long::longValue).average().orElseThrow();
		return BigDecimal.valueOf(Math.round(avg));
	}

	/** 광고 er = avg(likes+comments) * 100 / followers, 소수 1(HALF_UP). followers null/0이면 null. */
	private static BigDecimal adEr(List<SeriesRow> sponsored, Long followers) {
		if (followers == null || followers <= 0 || sponsored.isEmpty()) {
			return null;
		}
		double sum = 0;
		for (SeriesRow s : sponsored) {
			long likes = s.likes() == null ? 0 : s.likes();
			long comments = s.comments() == null ? 0 : s.comments();
			sum += likes + comments;
		}
		double avgEngagement = sum / sponsored.size();
		return BigDecimal.valueOf(avgEngagement * 100 / followers).setScale(1, RoundingMode.HALF_UP);
	}

	private static BigDecimal toBigDecimal(Long value) {
		return value == null ? null : BigDecimal.valueOf(value);
	}

	/**
	 * 유효 팔로워 = followers × min(1, 계정 ER / 기준 ER). 기준 = 피어 중앙값 ER(폴백 전체 중앙값).
	 * 휴리스틱(07-27 확정) — 정밀도보다 방향성. 근거 없으면 null(화면은 칸 숨김).
	 */
	static Long effectiveFollowers(Long followers, BigDecimal accountErPct, PeerStatsRow peer) {
		Double ratio = erRatio(followers, accountErPct, peer);
		if (ratio == null) {
			return null;
		}
		return Math.round(followers * ratio);
	}

	/** effectiveFollowers와 같은 ratio × 100 반올림. 산출 불가 시 null. */
	static Integer effectiveFollowersPct(Long followers, BigDecimal accountErPct, PeerStatsRow peer) {
		Double ratio = erRatio(followers, accountErPct, peer);
		if (ratio == null) {
			return null;
		}
		return (int) Math.round(ratio * 100);
	}

	/** peerSize < MIN_PEER_SIZE(예: 1명=자기 자신)이면 중앙값이 자기 ER과 같아져 ratio가 항상 1이 되므로
	 *  topPct와 같은 최소표본 게이트를 반드시 통과시킨다 — 우회하면 유효 팔로워가 늘 원 팔로워와 같아진다. */
	private static Double erRatio(Long followers, BigDecimal accountErPct, PeerStatsRow peer) {
		if (followers == null || accountErPct == null || peer == null || peer.peerSize() < MIN_PEER_SIZE) {
			return null;
		}
		BigDecimal ref = peer.peerMedianErPct() != null ? peer.peerMedianErPct() : peer.globalMedianErPct();
		if (ref == null || ref.signum() <= 0) {
			return null;
		}
		return Math.min(1.0, accountErPct.doubleValue() / ref.doubleValue());
	}

	private InfluencerAiReport.Chart toChart(SummaryRow summary, List<SeriesRow> series) {
		return new InfluencerAiReport.Chart(summary.metric(),
				series.stream()
						.map(p -> new InfluencerAiReport.Chart.Bar(p.views(), p.likes(), p.comments(),
								kstDate(p.postedAt()), p.sponsored(), p.contentType(), p.caption(),
								p.thumbnailUrl(), p.brand()))
						.toList());
	}

	private InfluencerAiReport.ContentMix toContentMix(CopyRow copy, List<CategoryRow> categories) {
		return new InfluencerAiReport.ContentMix(
				copy == null ? null : copy.contentSummary(),
				categories.stream()
						.map(c -> new InfluencerAiReport.ContentMix.Category(c.label(), c.cnt()))
						.toList(),
				traits(copy));
	}

	private InfluencerAiReport.Ads toAds(SummaryRow summary, CopyRow copy, List<SeriesRow> series,
			List<BrandRow> brands, List<ProductRow> products, OffsetDateTime now) {
		List<SeriesRow> sponsored = series.stream().filter(s -> Boolean.TRUE.equals(s.sponsored())).toList();
		OffsetDateTime lastAd = sponsored.stream()
				.map(SeriesRow::postedAt)
				.max(Comparator.naturalOrder()).orElse(null);
		Long lastAdDaysAgo = daysSince(lastAd, now);
		BigDecimal adIntervalDays = adIntervalDays(sponsored);
		String topBrand = brands.isEmpty() ? null : brands.get(0).name();
		return new InfluencerAiReport.Ads(
				copy == null ? null : copy.adSummary(),
				(long) sponsored.size(),
				series.stream().map(s -> Boolean.TRUE.equals(s.sponsored())).toList(),
				lastAdNote(lastAd, now),
				adIntervalDays,
				lastAdDaysAgo,
				headline(lastAdDaysAgo, adIntervalDays, topBrand),
				brands.stream().map(b -> new InfluencerAiReport.Ads.Brand(b.name(), b.cnt())).toList(),
				products.stream().map(p -> new InfluencerAiReport.Ads.Product(p.name(), p.cnt())).toList());
	}

	/** 스팬일수 / (건수-1), 소수 1(HALF_UP). 광고 2건 미만이거나 전부 같은 날(스팬 0)이면
	 *  "평균 0일 간격" 같은 오해 소지 문구를 막기 위해 null. */
	private static BigDecimal adIntervalDays(List<SeriesRow> sponsored) {
		if (sponsored.size() < 2) {
			return null;
		}
		OffsetDateTime min = sponsored.stream().map(SeriesRow::postedAt).min(Comparator.naturalOrder()).orElseThrow();
		OffsetDateTime max = sponsored.stream().map(SeriesRow::postedAt).max(Comparator.naturalOrder()).orElseThrow();
		long spanDays = ChronoUnit.DAYS.between(min, max);
		if (spanDays == 0) {
			return null;
		}
		return BigDecimal.valueOf(spanDays)
				.divide(BigDecimal.valueOf(sponsored.size() - 1), 1, RoundingMode.HALF_UP);
	}

	/** 광고 헤드라인 — 사실값 템플릿(07-27 확정, LLM 아님). 광고 이력 없으면 null. */
	static String headline(Long lastAdDaysAgo, BigDecimal adIntervalDays, String topBrand) {
		if (lastAdDaysAgo == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		sb.append(lastAdDaysAgo == 0 ? "오늘" : "최근 " + lastAdDaysAgo + "일 전");
		sb.append(topBrand != null ? " " + topBrand + " 협업" : " 광고 게시");
		if (adIntervalDays != null) {
			sb.append(" · 평균 ").append(adIntervalDays.setScale(0, RoundingMode.HALF_UP))
					.append("일 간격으로 광고 진행");
		}
		return sb.toString();
	}

	private InfluencerAiReport.Activity toActivity(SummaryRow summary, OffsetDateTime now) {
		Long lastUploadDaysAgo = daysSince(summary.lastPostedAt(), now);
		boolean isActive = lastUploadDaysAgo != null && lastUploadDaysAgo <= ACTIVE_THRESHOLD_DAYS;
		return new InfluencerAiReport.Activity(lastUploadDaysAgo, isActive, summary.avgIntervalDays());
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
