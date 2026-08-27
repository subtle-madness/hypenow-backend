package com.celfit.was.monitoring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * 주간 다이제스트 항목 조립(설계 §3) - 섹션 3개(확인 필요·브랜드 소식·모니터링 진행)와
 * 선택 노출 하이라이트 1건을 만든다. <b>내용이 있는 항목만</b> 남기므로, 결과가 빈 목록이면
 * 그 주는 알림을 만들지 않는다는 판정이 호출부에서 그대로 성립한다.
 *
 * <p>DB를 모르는 순수 컴포넌트다 - 문안·합산·하이라이트 규칙의 회귀는 전부 단위 테스트가 잡는다.
 *
 * <p>지표 표기 규칙 둘(설계 §3): ① 섹션별 <b>합산 한 줄까지만</b> 담고 개별 게시물은 나열하지
 * 않는다(상세는 딥링크). ② 조회수는 릴스만 집계된다 - 피드는 관측 자체가 NULL이라 content_type이
 * REELS가 아닌 행의 views는 있더라도 버린다. 그 주에 릴스가 없으면 views 합계는 null이고,
 * 문안·메일은 그 숫자를 아예 빼고 렌더링한다.
 */
@Component
public class WeeklyDigestAssembler {

	static final String CATEGORY_ACTION = "action_needed";
	static final String CATEGORY_BRAND = "brand";
	static final String CATEGORY_CONTENT = "content";
	static final String CATEGORY_HIGHLIGHT = "highlight";

	public List<DigestItem> assemble(WeeklyDigestInput input) {
		List<DigestItem> items = new ArrayList<>();
		// 1. 확인 필요 - 사용자가 손을 대야 하는 것부터 위에 온다.
		add(items, CATEGORY_ACTION, "ad_not_disclosed", "광고 표기가 없는 등록 게시물이 있어요",
				input.adNotDisclosedShortCodes().size(), null);
		add(items, CATEGORY_ACTION, "content_issue", "게시물을 확인하지 못한 콘텐츠가 있어요",
				count(input, "content_issue"), null);
		add(items, CATEGORY_ACTION, "metrics_private", "일부 지표가 비공개로 바뀐 콘텐츠가 있어요",
				count(input, "metrics_private"), null);
		// 2. 브랜드 소식
		add(items, CATEGORY_BRAND, "brand_new_posts", "브랜드를 언급한 새 게시물을 찾았어요",
				input.brandNewPosts().size(), sum(input.brandNewPosts()));
		// 3. 모니터링 진행 - 캠페인 이름 문맥은 두 항목에 같은 목록으로 붙인다.
		add(items, CATEGORY_CONTENT, "collection_started",
				withCampaigns("새로 수집을 시작한 콘텐츠가 있어요", input.campaignNames()),
				count(input, "collection_started"), null);
		add(items, CATEGORY_CONTENT, "collection_ended",
				withCampaigns("모니터링 기간이 끝난 콘텐츠가 있어요", input.campaignNames()),
				count(input, "collection_ended"), sum(input.endedPosts()));
		// 4. 하이라이트(선택 노출)
		highlight(input).ifPresent(items::add);
		return List.copyOf(items);
	}

	/** 건수가 0이면 항목 자체를 만들지 않는다 - "내용이 있는 섹션만 노출"(설계 §3)의 구현 지점. */
	private static void add(List<DigestItem> items, String category, String type, String summary,
			int count, DigestItem.Metrics metrics) {
		if (count > 0) {
			items.add(new DigestItem(category, type, summary, count, metrics));
		}
	}

	private static int count(WeeklyDigestInput input, String frontType) {
		return Math.toIntExact(input.eventCounts().getOrDefault(frontType, 0L));
	}

	/** 릴스만 조회수로 인정한다 - 피드는 관측이 항상 NULL이라 값이 들어와도 신뢰하지 않는다. */
	private static Long viewsOf(WeeklyPostMetrics post) {
		return "REELS".equalsIgnoreCase(post.contentType()) ? post.views() : null;
	}

	private static DigestItem.Metrics sum(List<WeeklyPostMetrics> posts) {
		if (posts.isEmpty()) {
			return null;
		}
		return new DigestItem.Metrics(sumOrNull(posts, WeeklyDigestAssembler::viewsOf),
				sumOrNull(posts, WeeklyPostMetrics::likes), sumOrNull(posts, WeeklyPostMetrics::comments));
	}

	/** 값이 하나도 없으면 0이 아니라 null - 0은 "실제로 0"이라는 거짓말이 된다. */
	private static Long sumOrNull(List<WeeklyPostMetrics> posts, Function<WeeklyPostMetrics, Long> extractor) {
		List<Long> values = posts.stream().map(extractor).filter(java.util.Objects::nonNull).toList();
		return values.isEmpty() ? null : values.stream().mapToLong(Long::longValue).sum();
	}

	/** 이번 주 등장 게시물(새 발견 + 수집 종료) 중 최고 지표 1건. 조회수 우선, 없으면 좋아요. */
	private static Optional<DigestItem> highlight(WeeklyDigestInput input) {
		List<WeeklyPostMetrics> candidates = new ArrayList<>(input.brandNewPosts());
		candidates.addAll(input.endedPosts());
		Optional<WeeklyPostMetrics> byViews = candidates.stream()
				.filter(post -> viewsOf(post) != null)
				.max(Comparator.comparingLong(post -> viewsOf(post)));
		if (byViews.isPresent()) {
			WeeklyPostMetrics top = byViews.get();
			return Optional.of(new DigestItem(CATEGORY_HIGHLIGHT, "top_post",
					"@%s 게시물 · 조회수 %s".formatted(top.authorUsername(), formatCount(viewsOf(top))), 1,
					new DigestItem.Metrics(viewsOf(top), top.likes(), top.comments())));
		}
		return candidates.stream()
				.filter(post -> post.likes() != null)
				.max(Comparator.comparingLong(WeeklyPostMetrics::likes))
				.map(top -> new DigestItem(CATEGORY_HIGHLIGHT, "top_post",
						"@%s 게시물 · 좋아요 %s".formatted(top.authorUsername(), formatCount(top.likes())), 1,
						new DigestItem.Metrics(null, top.likes(), top.comments())));
	}

	/**
	 * 만 단위 축약 - 1만 미만은 천단위 구분 그대로, 그 이상은 소수 첫째 자리까지(버림).
	 * 정수 연산으로 계산한다(double 반올림 오차가 문안에 드러나지 않게).
	 */
	static String formatCount(long value) {
		if (value < 10_000) {
			return String.format(Locale.KOREA, "%,d", value);
		}
		long tenthsOfMan = value / 1_000;
		long man = tenthsOfMan / 10;
		long fraction = tenthsOfMan % 10;
		return fraction == 0 ? man + "만" : man + "." + fraction + "만";
	}

	/**
	 * 캠페인 이름 문맥(설계 §2 "다이제스트 문안에 캠페인 이름 문맥만 반영"). 셋 이상이면 문안이
	 * 길어져 요약이 아니게 되므로 둘까지만 적고 나머지는 건수로 접는다.
	 * 엠대시 금지 규칙에 따라 구분은 쉼표와 괄호만 쓴다.
	 */
	static String withCampaigns(String base, List<String> names) {
		if (names.isEmpty()) {
			return base;
		}
		if (names.size() <= 2) {
			return base + " (" + String.join(", ", names) + ")";
		}
		return base + " (" + names.get(0) + ", " + names.get(1) + " 외 " + (names.size() - 2) + "건)";
	}
}
