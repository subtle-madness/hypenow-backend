package com.celfit.was.v1.perfdashboard;

import com.celfit.was.v1.brandmonitoring.BrandSponsorshipClassifier;
import com.celfit.was.v1.perfdashboard.PerformanceContentAssembler.DashboardRef;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 인플루언서 집계(스펙 2026-08-27 §4) — 목록과 같은 인덱스 ref를 handle로 그룹핑하는 순수
 * 함수다(DB 신규 쿼리 없음). 지표는 ref가 이미 들고 있는 <b>최신 스냅샷</b> 유래 값이라
 * 여기서 스냅샷을 다시 고르지 않는다.
 *
 * <p>정렬·페이지네이션은 하지 않는다(호출부 몫) — 대신 결과는 입력 순서와 무관하게
 * 결정적이다: handle 그룹의 <b>등장 순</b>을 유지한다(LinkedHashMap).
 */
public final class PerformanceInfluencerAggregator {

	private PerformanceInfluencerAggregator() {
	}

	/** 필터 적용 후 ref 전량 → handle별 집계 1행씩. handle 미상(null·공백) ref는 제외한다. */
	public static List<PerformanceInfluencerResponse> aggregate(List<DashboardRef> refs) {
		Map<String, List<DashboardRef>> byHandle = new LinkedHashMap<>();
		for (DashboardRef ref : refs) {
			// handle은 인덱스 패스에서 이미 소문자로 정규화된 계약(PR ①)이라 여기서 재정규화하지
			// 않는다 — 방어적 toLowerCase는 정규화 지점을 이원화해 불일치를 숨긴다.
			String handle = ref.handle();
			if (handle == null || handle.isBlank()) {
				continue;
			}
			byHandle.computeIfAbsent(handle, key -> new ArrayList<>()).add(ref);
		}
		List<PerformanceInfluencerResponse> rows = new ArrayList<>(byHandle.size());
		byHandle.forEach((handle, group) -> rows.add(aggregateOne(handle, group)));
		return List.copyOf(rows);
	}

	/** handle 1명 집계 — 규칙 2~8을 그룹 안에서만 계산한다. */
	private static PerformanceInfluencerResponse aggregateOne(String handle, List<DashboardRef> group) {
		int sponsoredCount = 0;
		int likesKnownCount = 0;
		Long views = null;
		Long likes = null;
		Long comments = null;
		Long ratedFollowers = null;
		Long ratedEngaged = null;
		LocalDate latestPostOn = null;
		Set<String> brandAccountIds = new LinkedHashSet<>();

		for (DashboardRef ref : group) {
			if (BrandSponsorshipClassifier.SPONSORED.equals(ref.sponsorship())) {
				sponsoredCount++;
			}
			if (ref.brandAccountId() != null) {
				brandAccountIds.add(ref.brandAccountId());
			}
			if (ref.uploadedOn() != null && (latestPostOn == null || ref.uploadedOn().isAfter(latestPostOn))) {
				latestPostOn = ref.uploadedOn();
			}
			// 지표 합산은 스냅샷 있는 게시물만 — 관측 전무는 "0"이 아니라 모수 밖이다.
			if (!ref.hasSnapshots()) {
				continue;
			}
			views = accumulate(views, ref.latestViews());
			comments = accumulate(comments, ref.latestComments());

			boolean likesKnown = !ref.latestLikesHidden() && ref.latestLikes() != null;
			if (likesKnown) {
				likes = accumulate(likes, ref.latestLikes());
				likesKnownCount++;
			}
			// 참여율 분모·분자는 팔로워·좋아요·댓글을 모두 아는 게시물만 — 게시물마다 팔로워 1회.
			if (likesKnown && ref.followers() != null && ref.latestComments() != null) {
				ratedFollowers = accumulate(ratedFollowers, ref.followers());
				ratedEngaged = accumulate(ratedEngaged, ref.latestLikes() + ref.latestComments());
			}
		}

		Display display = pickDisplay(handle, group);
		return new PerformanceInfluencerResponse(handle, display.displayName(), display.profileImageUrl(),
				display.followers(), group.size(), sponsoredCount, likesKnownCount,
				latestPostOn == null ? null : latestPostOn.toString(),
				views, likes, comments, ratedFollowers, ratedEngaged, List.copyOf(brandAccountIds));
	}

	/** 대표 표시값(규칙 7) — 필드별로 독립 선정이라 최신 ref가 일부만 채워도 나머지가 살아난다. */
	private record Display(String displayName, String profileImageUrl, Long followers) {
	}

	/**
	 * 업로드 최신 ref 우선으로 필드별 첫 non-null을 고른다 — 같은 작성자의 관측이 산지·시점별로
	 * 들쭉날쭉해도 최신 관측이 이긴다. 업로드일 미상 ref는 최신성을 주장할 근거가 없어 마지막
	 * 순번으로 밀린다. displayName은 빈 문자열도 부재로 보고(상류 폴백 {@code refOfPoolRow}·
	 * {@code fromBrandPost}와 같은 술어) 전부 비면 handle로 폴백한다 — 빈 이름이 이기면 FE가
	 * 공백을 렌더한다. profileImageUrl·followers는 blank 개념이 없어 null만 본다.
	 */
	private static Display pickDisplay(String handle, List<DashboardRef> group) {
		List<DashboardRef> latestFirst = new ArrayList<>(group);
		latestFirst.sort(Comparator.comparing(DashboardRef::uploadedOn,
				Comparator.nullsLast(Comparator.reverseOrder())));

		String displayName = null;
		String profileImageUrl = null;
		Long followers = null;
		for (DashboardRef ref : latestFirst) {
			if (displayName == null && ref.displayName() != null && !ref.displayName().isBlank()) {
				displayName = ref.displayName();
			}
			if (profileImageUrl == null) {
				profileImageUrl = ref.profileImageUrl();
			}
			if (followers == null) {
				followers = ref.followers();
			}
		}
		return new Display(displayName == null ? handle : displayName, profileImageUrl, followers);
	}

	/** null 유지 합산 — 첫 non-null에서 합이 시작되고, value가 null이면 sum을 건드리지 않는다. */
	private static Long accumulate(Long sum, Long value) {
		if (value == null) {
			return sum;
		}
		return sum == null ? value : sum + value;
	}
}
