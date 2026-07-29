package com.celfit.was.v1.influencer;

import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.BrandRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.CardRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.EngagementRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.ShareRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.ThumbRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 행 4종 보강 결합 → InfluencerCard(스펙 6.21) 순수 변환. 본 쿼리의 정렬 순서를 그대로 보존한다.
 * 유효 팔로워는 EffectiveFollowers(실반응 산식, 스펙 7절 17번 — 6.22 리포트와 동일 소스)로 계산한다.
 */
@Component
public class V1InfluencerDiscoveryAssembler {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	public List<InfluencerCard> toCards(List<CardRow> rows, List<ShareRow> shares,
			List<BrandRow> brands, List<ThumbRow> thumbs, List<EngagementRow> engagements) {
		Map<String, List<ShareRow>> sharesBy = shares.stream()
				.collect(Collectors.groupingBy(ShareRow::accountHandle));
		Map<String, List<BrandRow>> brandsBy = brands.stream()
				.collect(Collectors.groupingBy(BrandRow::accountHandle));
		Map<String, List<ThumbRow>> thumbsBy = thumbs.stream()
				.collect(Collectors.groupingBy(ThumbRow::accountHandle));
		Map<String, List<EngagementRow>> engagementsBy = engagements.stream()
				.collect(Collectors.groupingBy(EngagementRow::accountHandle));
		return rows.stream().map(r -> toCard(r,
				sharesBy.getOrDefault(r.handle(), List.of()),
				brandsBy.getOrDefault(r.handle(), List.of()),
				thumbsBy.getOrDefault(r.handle(), List.of()),
				engagementsBy.getOrDefault(r.handle(), List.of()))).toList();
	}

	private InfluencerCard toCard(CardRow r, List<ShareRow> shares, List<BrandRow> brands,
			List<ThumbRow> thumbs, List<EngagementRow> engagements) {
		return new InfluencerCard(
				r.handle(), r.handle(), r.displayName(), r.profileImageUrl(),
				r.followers(),
				EffectiveFollowers.estimate(r.followers(), engagements.stream()
						.map(e -> new EffectiveFollowers.Post(e.views(), e.likes(), e.comments()))
						.toList()),
				r.postsCount(), r.followsCount(),
				blankIfNull(r.biography()),
				null, // email — 크롤러 미수집(V31), 데이터가 생기면 배선
				blankIfNull(r.tagline()),
				scale1(r.viewsPerFollower()), scale1(r.avgErPct()),
				r.avgViews(), r.avgLikes(), r.avgComments(),
				r.avgHypeScore() == null ? null : r.avgHypeScore().intValue(),
				r.sponsoredCount(),
				brands.stream().map(BrandRow::name).toList(),
				shares.stream().limit(3)
						.map(s -> new InfluencerCard.CategoryShare(s.mainCategory(), s.pct()))
						.toList(),
				thumbs.stream().map(this::toThumb).toList());
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
