package com.celfit.was.monitoring;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 주간 리포트 메일 문안(설계 §6) — 인앱 다이제스트와 <b>같은 항목</b>을 텍스트로 편다.
 * 구 임시 카피(monitoring AlarmMailComposer)를 대체하는 정식 문안이며 딥링크를 포함한다.
 *
 * <p>사용자向 문안이라 엠대시를 쓰지 않는다(프로젝트 규칙) - 구분은 " - "와 가운뎃점(·)이다.
 * 항목 문안·건수·합산 지표의 정본은 {@link WeeklyDigestAssembler}가 만든 항목 그 자체다.
 * 이 클래스는 표현만 담당한다.
 */
@Component
public class WeeklyDigestMailComposer {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREA);

	/** 렌더 순서 - 조립기의 섹션 순서와 같아야 인앱·메일이 어긋나지 않는다. */
	private static final List<String> SECTION_ORDER = List.of(
			WeeklyDigestAssembler.CATEGORY_ACTION, WeeklyDigestAssembler.CATEGORY_BRAND,
			WeeklyDigestAssembler.CATEGORY_CONTENT, WeeklyDigestAssembler.CATEGORY_HIGHLIGHT);

	private static final Map<String, String> SECTION_TITLES = Map.of(
			WeeklyDigestAssembler.CATEGORY_ACTION, "확인 필요",
			WeeklyDigestAssembler.CATEGORY_BRAND, "브랜드 소식",
			WeeklyDigestAssembler.CATEGORY_CONTENT, "모니터링 진행",
			WeeklyDigestAssembler.CATEGORY_HIGHLIGHT, "이번 주 하이라이트");

	private final String webBaseUrl;

	public WeeklyDigestMailComposer(@Value("${was.web.base-url:https://hypenow.io}") String webBaseUrl) {
		this.webBaseUrl = webBaseUrl;
	}

	public record Mail(String subject, String text) {
	}

	public Mail compose(WeekWindow window, List<DigestItem> items) {
		String period = DAY.format(window.startDate()) + " - " + DAY.format(window.endDateInclusive());
		StringBuilder text = new StringBuilder();
		text.append("안녕하세요. 지난주(").append(period).append(") 하입나우 모니터링 요약이에요.\n");
		for (String category : SECTION_ORDER) {
			List<DigestItem> section = items.stream()
					.filter(item -> category.equals(item.category()))
					.toList();
			if (section.isEmpty()) {
				continue;   // 내용이 있는 섹션만 노출(설계 §3)
			}
			text.append('\n').append('[').append(SECTION_TITLES.get(category)).append("]\n");
			boolean highlight = WeeklyDigestAssembler.CATEGORY_HIGHLIGHT.equals(category);
			for (DigestItem item : section) {
				text.append("- ").append(item.summary());
				if (!highlight) {
					// 하이라이트는 "1건"이 정보가 아니라 소음이다 - 요약 문장 자체가 내용이다.
					text.append(" : ").append(item.count()).append("건");
				}
				text.append('\n');
				String metrics = highlight ? null : metricsLine(item.metrics());
				if (metrics != null) {
					text.append("  ").append(metrics).append('\n');
				}
			}
		}
		text.append("\n자세한 내용은 ").append(webBaseUrl).append("/notifications 에서 확인할 수 있어요.\n");
		text.append("주간 이메일 수신은 ").append(webBaseUrl)
				.append("/settings/notifications 에서 끌 수 있어요.\n");
		return new Mail("[hypenow] 지난주 모니터링 요약 (" + period + ")", text.toString());
	}

	/**
	 * 섹션 합산 한 줄. 조회수는 릴스만 집계되므로 그 주에 릴스가 없으면 views가 null이고,
	 * 그때는 그 숫자를 아예 빼고 렌더링한다(설계 §3). 셋 다 없으면 줄 자체를 만들지 않는다.
	 */
	private static String metricsLine(DigestItem.Metrics metrics) {
		if (metrics == null) {
			return null;
		}
		List<String> parts = new ArrayList<>();
		if (metrics.views() != null) {
			parts.add("조회수(릴스) " + number(metrics.views()));
		}
		if (metrics.likes() != null) {
			parts.add("좋아요 " + number(metrics.likes()));
		}
		if (metrics.comments() != null) {
			parts.add("댓글 " + number(metrics.comments()));
		}
		return parts.isEmpty() ? null : String.join(" · ", parts);
	}

	private static String number(long value) {
		return String.format(Locale.KOREA, "%,d", value);
	}
}
