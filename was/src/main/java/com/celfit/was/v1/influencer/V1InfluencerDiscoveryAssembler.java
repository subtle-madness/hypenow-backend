package com.celfit.was.v1.influencer;

import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.BrandRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.CardRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.EngagementRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.GroupPurchaseCountRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.ShareRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.ThumbRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 행 5종 보강 결합 → InfluencerCard(스펙 6.21) 순수 변환. 본 쿼리의 정렬 순서를 그대로 보존한다.
 * 유효 팔로워는 EffectiveFollowers(실반응 산식, 스펙 7절 17번 — 6.22 리포트와 동일 소스)로 계산한다.
 */
@Component
public class V1InfluencerDiscoveryAssembler {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	public List<InfluencerCard> toCards(List<CardRow> rows, List<ShareRow> shares,
			List<BrandRow> brands, List<ThumbRow> thumbs, List<EngagementRow> engagements,
			List<GroupPurchaseCountRow> groupPurchaseCounts) {
		Map<String, List<ShareRow>> sharesBy = shares.stream()
				.collect(Collectors.groupingBy(ShareRow::accountHandle));
		Map<String, List<BrandRow>> brandsBy = brands.stream()
				.collect(Collectors.groupingBy(BrandRow::accountHandle));
		Map<String, List<ThumbRow>> thumbsBy = thumbs.stream()
				.collect(Collectors.groupingBy(ThumbRow::accountHandle));
		Map<String, List<EngagementRow>> engagementsBy = engagements.stream()
				.collect(Collectors.groupingBy(EngagementRow::accountHandle));
		// 핸들당 최대 1행(리포지토리가 이미 GROUP BY account_handle) — 판정 행이 아예 없는 핸들은
		// 맵에 없고, 그 경우 0건으로 취급한다(아래 getOrDefault).
		Map<String, Long> groupPurchaseCountsBy = groupPurchaseCounts.stream()
				.collect(Collectors.toMap(GroupPurchaseCountRow::accountHandle, GroupPurchaseCountRow::count));
		return rows.stream().map(r -> toCard(r,
				sharesBy.getOrDefault(r.handle(), List.of()),
				brandsBy.getOrDefault(r.handle(), List.of()),
				thumbsBy.getOrDefault(r.handle(), List.of()),
				engagementsBy.getOrDefault(r.handle(), List.of()),
				groupPurchaseCountsBy.getOrDefault(r.handle(), 0L))).toList();
	}

	private InfluencerCard toCard(CardRow r, List<ShareRow> shares, List<BrandRow> brands,
			List<ThumbRow> thumbs, List<EngagementRow> engagements, long groupPurchaseCount) {
		List<Long> windowComments = engagements.stream()
				.map(EngagementRow::comments).filter(Objects::nonNull).toList();
		return new InfluencerCard(
				r.handle(), r.handle(), r.displayName(), r.profileImageUrl(),
				r.followers(),
				EffectiveFollowers.estimate(r.followers(), engagements.stream()
						.map(e -> new EffectiveFollowers.Post(e.views(), e.likes(), e.comments()))
						.toList()),
				r.postsCount(), r.followsCount(),
				blankIfNull(r.biography()),
				r.email(), // biography 정규식 파싱(V46) — 매치 없으면 null
				blankIfNull(r.tagline()),
				scale1(r.viewsPerFollower()), scale1(r.avgErPct()),
				r.avgViews(), r.avgLikes(), r.avgComments(),
				minOf(windowComments), maxOf(windowComments),
				r.avgHypeScorePrecise(),
				r.sponsoredCount(),
				(int) groupPurchaseCount, groupPurchaseCount > 0,
				brands.stream().map(BrandRow::name).toList(),
				shares.stream().limit(3)
						.map(s -> new InfluencerCard.CategoryShare(s.mainCategory(), s.pct()))
						.toList(),
				thumbs.stream().map(this::toThumb).toList());
	}

	// avgComments와 동일 모수(account_content_series, comments NULL 제외)에서 min/max — 표본 0이면 null.
	private static Integer minOf(List<Long> values) {
		return values.isEmpty() ? null : values.stream().min(Comparator.naturalOrder()).map(Long::intValue).orElse(null);
	}

	private static Integer maxOf(List<Long> values) {
		return values.isEmpty() ? null : values.stream().max(Comparator.naturalOrder()).map(Long::intValue).orElse(null);
	}

	private InfluencerCard.RecentThumb toThumb(ThumbRow t) {
		return new InfluencerCard.RecentThumb(t.shortCode(), t.thumbnailUrl(), t.contentType(),
				t.mainCategory(), t.adType(),
				t.postedAt() == null ? null
						: t.postedAt().atZoneSameInstant(KST).toLocalDate().toString(),
				t.views(), t.likes(), t.comments());
	}

	private static String blankIfNull(String v) {
		return v == null ? "" : v;
	}

	private static BigDecimal scale1(BigDecimal v) {
		return v == null ? null : v.setScale(1, RoundingMode.HALF_UP);
	}
}
