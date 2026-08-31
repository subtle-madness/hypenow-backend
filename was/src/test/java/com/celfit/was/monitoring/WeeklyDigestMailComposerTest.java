package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 주간 리포트 메일 문안(설계 §6) — 섹션 순서·합산 한 줄·딥링크·엠대시 금지. */
class WeeklyDigestMailComposerTest {

	private final WeeklyDigestMailComposer composer = new WeeklyDigestMailComposer("https://hypenow.io");
	private final WeekWindow window = new WeekWindow(LocalDate.of(2026, 8, 17));

	@Test
	void 제목에_지난주_기간이_들어간다() {
		WeeklyDigestMailComposer.Mail mail = composer.compose(window, List.of(
				new DigestItem("content", "collection_started", "새로 수집을 시작한 콘텐츠가 있어요", 2, null)));

		assertThat(mail.subject()).isEqualTo("[hypenow] 지난주 모니터링 요약 (8월 17일 - 8월 23일)");
	}

	@Test
	void 섹션_제목과_건수가_본문에_들어간다() {
		WeeklyDigestMailComposer.Mail mail = composer.compose(window, List.of(
				new DigestItem("action_needed", "ad_not_disclosed", "광고 표기가 없는 등록 게시물이 있어요", 3, null),
				new DigestItem("content", "collection_started", "새로 수집을 시작한 콘텐츠가 있어요", 2, null)));

		assertThat(mail.text())
				.contains("[확인 필요]")
				.contains("- 광고 표기가 없는 등록 게시물이 있어요 : 3건")
				.contains("[모니터링 진행]")
				.contains("- 새로 수집을 시작한 콘텐츠가 있어요 : 2건");
	}

	@Test
	void 내용이_없는_섹션은_본문에_나오지_않는다() {
		WeeklyDigestMailComposer.Mail mail = composer.compose(window, List.of(
				new DigestItem("content", "collection_started", "새로 수집을 시작한 콘텐츠가 있어요", 2, null)));

		assertThat(mail.text()).doesNotContain("[확인 필요]").doesNotContain("[브랜드 소식]");
	}

	@Test
	void 합산_지표는_항목_아래_한_줄로_붙는다() {
		WeeklyDigestMailComposer.Mail mail = composer.compose(window, List.of(
				new DigestItem("brand", "brand_new_posts", "브랜드를 언급한 새 게시물을 찾았어요", 12,
						new DigestItem.Metrics(123456L, 7890L, 123L))));

		assertThat(mail.text()).contains("  조회수(릴스) 12.3만 · 좋아요 7,890 · 댓글 123");
	}

	@Test
	void 조회수가_없으면_그_숫자를_빼고_렌더링한다() {
		WeeklyDigestMailComposer.Mail mail = composer.compose(window, List.of(
				new DigestItem("brand", "brand_new_posts", "브랜드를 언급한 새 게시물을 찾았어요", 3,
						new DigestItem.Metrics(null, 30L, 3L))));

		assertThat(mail.text()).contains("  좋아요 30 · 댓글 3").doesNotContain("조회수");
	}

	@Test
	void 하이라이트는_건수와_지표_줄_없이_요약만_적는다() {
		WeeklyDigestMailComposer.Mail mail = composer.compose(window, List.of(
				new DigestItem("highlight", "top_post", "@big 게시물 · 조회수 12.3만", 1,
						new DigestItem.Metrics(123456L, 10L, 1L))));

		assertThat(mail.text())
				.contains("[지난주 하이라이트]")
				.contains("- @big 게시물 · 조회수 12.3만")
				.doesNotContain("1건")
				.doesNotContain("조회수(릴스)");
	}

	@Test
	void 딥링크와_수신_해지_안내가_들어간다() {
		WeeklyDigestMailComposer.Mail mail = composer.compose(window, List.of(
				new DigestItem("content", "collection_started", "새로 수집을 시작한 콘텐츠가 있어요", 1, null)));

		assertThat(mail.text())
				.contains("https://hypenow.io/dashboard?utm_source=email&utm_medium=weekly_digest")
				.contains("https://hypenow.io/settings?utm_source=email&utm_medium=weekly_digest");
	}

	@Test
	void 문안에_엠대시가_없다() {
		WeeklyDigestMailComposer.Mail mail = composer.compose(window, List.of(
				new DigestItem("action_needed", "ad_not_disclosed", "광고 표기가 없는 등록 게시물이 있어요", 1, null),
				new DigestItem("brand", "brand_new_posts", "브랜드를 언급한 새 게시물을 찾았어요", 1,
						new DigestItem.Metrics(1L, 1L, 1L)),
				new DigestItem("highlight", "top_post", "@a 게시물 · 좋아요 1", 1, null)));

		assertThat(mail.subject()).doesNotContain("—");
		assertThat(mail.text()).doesNotContain("—");
	}
}
