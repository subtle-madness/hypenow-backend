package com.celfit.was.v1.brandmonitoring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.AuthorRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandCommentRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandPostMetaRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandSnapshotRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandTaggedPostRow;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import com.celfit.was.v1.monitoring.TrackingItemAssembler;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * /v1/brand-monitoring 게시물 표면 계약(스펙 §6-1·§6-2) — counts 정합, 필터·정렬, 소유권 403,
 * 미소유 게시물 404를 고정한다. 어셈블러는 실 빈으로 붙이고 DB 접점(BrandReadRepository·
 * BrandDirectPostRepository)과 레거시 조립기만 mock한다 — 조립·병합·필터 전 구간이 실제로 돈다.
 */
@WebMvcTest(controllers = V1BrandPostsController.class,
		properties = {"was.cors.allowed-origins=http://localhost:3000", "monitoring.enabled=true"})
@Import({BrandPostAssembler.class, V1ExceptionAdvice.class, SecurityConfig.class})
class V1BrandPostsControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	BrandLinkRepository linkRepository;
	@MockitoBean
	BrandReadRepository brandReadRepository;
	@MockitoBean
	BrandDirectPostRepository directPostRepository;
	@MockitoBean
	TrackingItemAssembler trackingItemAssembler;

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	@BeforeEach
	void ownedBrand() {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link()));
		given(linkRepository.findActiveByUser(7L)).willReturn(Optional.of(link()));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(account()));
	}

	// ---------- 목록 ----------

	@Test
	void 목록은_tagged와_direct를_합치고_counts는_필터_전_전량이다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"), taggedRow("BBB", "2026-08-05T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(
				meta("AAA", "REELS", true), meta("BBB", "FEED", false)));
		givenDirect("XYZ", 42L);

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(3))
				// 기본 정렬은 업로드 최신순 — direct(8/6 업로드)와 tagged AAA(8/6)는 shortcode 타이브레이크.
				.andExpect(jsonPath("$.data[*].shortcode").value(Matchers.contains("AAA", "XYZ", "BBB")))
				.andExpect(jsonPath("$.meta.total").value(3))
				.andExpect(jsonPath("$.meta.limit").value(200))
				.andExpect(jsonPath("$.meta.counts.all").value(3))
				.andExpect(jsonPath("$.meta.counts.tagged").value(2))
				.andExpect(jsonPath("$.meta.counts.direct").value(1))
				.andExpect(jsonPath("$.meta.counts.sponsored").value(1))
				.andExpect(jsonPath("$.meta.counts.organic").value(1))
				.andExpect(jsonPath("$.meta.counts.unknown").value(1))
				.andExpect(jsonPath("$.meta.lastCollectedAt").value("2026-08-08T03:00:00+09:00"));
	}

	@Test
	void 목록은_tagged_게시물의_지표와_댓글을_함께_내려준다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("AAA", "REELS", true)));
		given(brandReadRepository.findSnapshots(any())).willReturn(List.of(
				snapshotRow("AAA", 5, 100L), snapshotRow("AAA", 6, 300L)));
		given(brandReadRepository.findComments(any(), anyInt())).willReturn(List.of(
				new BrandCommentRow("AAA", "c1", "glowdeep_92", "좋아요", 3L,
						OffsetDateTime.parse("2026-08-06T05:00:00Z"), null)));
		given(brandReadRepository.findAuthors(any())).willReturn(List.of(
				new AuthorRow("9001", "glowdeep_92", "글로우딥", 12345L, "https://cdn/author.jpg", true)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].postUrl").value("https://www.instagram.com/reel/AAA/"))
				.andExpect(jsonPath("$.data[0].sponsorship").value("sponsored"))
				.andExpect(jsonPath("$.data[0].trackingStatus").value("tracking"))
				.andExpect(jsonPath("$.data[0].authorFullName").value("글로우딥"))
				.andExpect(jsonPath("$.data[0].authorFollowers").value(12345))
				.andExpect(jsonPath("$.data[0].snapshots.length()").value(2))
				.andExpect(jsonPath("$.data[0].latestSnapshot.views").value(300))
				.andExpect(jsonPath("$.data[0].commentsTotal").value(12))
				.andExpect(jsonPath("$.data[0].commentsCollectedCount").value(1))
				.andExpect(jsonPath("$.data[0].recentComments[0].author").value("gl***92"))
				// nullable 키는 생략하지 않고 명시적 null(계약 무결성 규칙 #1).
				.andExpect(jsonPath("$.data[0]", Matchers.hasKey("trackingEndedAt")))
				.andExpect(jsonPath("$.data[0].trackingEndedAt").value(Matchers.nullValue()));
	}

	@Test
	void 협찬_필터는_data만_줄이고_counts는_그대로다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"), taggedRow("BBB", "2026-08-05T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(
				meta("AAA", "REELS", true), meta("BBB", "FEED", false)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?sponsorship=sponsored")
						.with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].shortcode").value("AAA"))
				.andExpect(jsonPath("$.meta.total").value(1))
				.andExpect(jsonPath("$.meta.counts.all").value(2));
	}

	@Test
	void source_필터는_direct만_남긴다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("AAA", "REELS", null)));
		givenDirect("XYZ", 42L);

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?source=direct").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].source").value("direct"))
				.andExpect(jsonPath("$.data[0].shortcode").value("XYZ"));
	}

	@Test
	void 업로드_기간_필터는_KST_날짜로_자른다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"), taggedRow("BBB", "2026-08-01T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(
				meta("AAA", "REELS", null), meta("BBB", "FEED", null)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?uploadedFrom=2026-08-05")
						.with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].shortcode").value("AAA"));
	}

	@Test
	void performance_desc는_최신_스냅샷_views_내림차순이고_null이_마지막이다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"), taggedRow("BBB", "2026-08-05T01:00:00Z"),
				taggedRow("CCC", "2026-08-04T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(
				meta("AAA", "REELS", null), meta("BBB", "REELS", null), meta("CCC", "REELS", null)));
		given(brandReadRepository.findSnapshots(any())).willReturn(List.of(
				snapshotRow("AAA", 6, 100L), snapshotRow("BBB", 6, 900L)));   // CCC는 스냅샷 없음

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?sort=performance_desc")
						.with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].shortcode").value(Matchers.contains("BBB", "AAA", "CCC")));
	}

	@Test
	void 알_수_없는_정렬키는_400이다() throws Exception {
		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?sort=likes_desc").with(user(principal())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 잘못된_날짜_형식은_400이다() throws Exception {
		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?uploadedTo=2026-13-99")
						.with(user(principal())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 남의_계정_목록은_403이고_조회하지_않는다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 999L)).willReturn(Optional.empty());

		mockMvc.perform(get("/v1/brand-monitoring/accounts/999/posts").with(user(principal())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		then(brandReadRepository).should(never()).findTaggedPostsInWindow(anyLong(), any(), anyInt());
	}

	@Test
	void 인증이_없으면_401이다() throws Exception {
		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts"))
				.andExpect(status().isUnauthorized());
	}

	// ---------- 상세 ----------

	@Test
	void 상세는_shortcode로_같은_조립을_돌려준다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("AAA", "REELS", true)));

		mockMvc.perform(get("/v1/brand-monitoring/posts/AAA").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value("AAA"))
				.andExpect(jsonPath("$.data.source").value("tagged"))
				.andExpect(jsonPath("$.data.sponsorship").value("sponsored"));
	}

	@Test
	void 내_tagged와_direct_어디에도_없는_게시물은_404다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("AAA", "REELS", null)));

		mockMvc.perform(get("/v1/brand-monitoring/posts/ZZZ").with(user(principal())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void 브랜드_연결이_없으면_상세는_404다() throws Exception {
		given(linkRepository.findActiveByUser(7L)).willReturn(Optional.empty());

		mockMvc.perform(get("/v1/brand-monitoring/posts/AAA").with(user(principal())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	// ---------- 스텁 ----------

	private void givenTagged(BrandTaggedPostRow... rows) {
		given(brandReadRepository.findTaggedPostsInWindow(anyLong(), any(), anyInt())).willReturn(List.of(rows));
	}

	/** 직접 등록 1건 — 매핑 행 + 레거시 조립 결과를 함께 스텁한다. */
	private void givenDirect(String shortCode, long itemId) {
		given(directPostRepository.findByUser(7L))
				.willReturn(List.of(new BrandDirectPostRepository.Row(7L, 100L, shortCode, itemId)));
		var snapshot = new TrackingItemResponse.SnapshotResponse("2026-08-06", 300L, 20L, false, 9L,
				4L, 2L, false, 1L);
		var post = new TrackingItemResponse.TrackedPostResponse("https://www.instagram.com/reel/" + shortCode + "/",
				"reels", "2026-08-06", "일상 기록", List.of(), "https://cdn/legacy-thumb.jpg", null,
				List.of(snapshot), List.of());
		var item = TrackingItemResponse.full(itemId, "url", "tracking", "glowdeep_92", "글로우딥",
				"https://cdn/author.jpg", 12345L, "2026-08-06", null, null,
				"https://www.instagram.com/reel/" + shortCode + "/", LocalDate.of(2026, 8, 1), 30, null, post, null);
		given(trackingItemAssembler.assembleList(7L)).willReturn(new TrackingItemAssembler.AssembledList(
				List.of(item), OffsetDateTime.parse("2026-08-07T17:00:00Z"), LocalDate.of(2026, 8, 8)));
	}

	private static BrandLinkRow link() {
		return new BrandLinkRow(1L, 7L, 100L, "lizda_official",
				OffsetDateTime.parse("2026-08-01T00:00:00Z"), null);
	}

	private static BrandAccountRow account() {
		return new BrandAccountRow(100L, "lizda_official", LocalDate.of(2026, 8, 8),
				OffsetDateTime.parse("2026-08-07T18:00:00Z"), OffsetDateTime.parse("2026-08-01T00:00:00Z"),
				OffsetDateTime.parse("2026-08-01T01:00:00Z"), null, 30876L, 12L, 340L, null, "리즈다",
				"https://cdn/pic.jpg", true, null, "ACTIVE");
	}

	private static BrandTaggedPostRow taggedRow(String code, String takenAt) {
		return new BrandTaggedPostRow(code, "glowdeep_92", "9001", OffsetDateTime.parse(takenAt),
				OffsetDateTime.parse("2026-08-06T02:00:00Z"), 7L);
	}

	private static BrandPostMetaRow meta(String code, String contentType, Boolean paid) {
		return new BrandPostMetaRow(code, "glowdeep_92", contentType, LocalDate.of(2026, 8, 6),
				"캡션 원문", "https://cdn/thumb.jpg", "https://cdn/video.mp4", 15.5, paid);
	}

	private static BrandSnapshotRow snapshotRow(String code, int day, Long views) {
		return new BrandSnapshotRow(code, LocalDate.of(2026, 8, day), "REELS", 10L, false, 12L, views,
				5L, 7L, false, 3L);
	}
}
