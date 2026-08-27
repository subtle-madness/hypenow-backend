package com.celfit.was.v1.brandmonitoring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
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
import com.celfit.was.monitoring.BrandPostCampaignRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandPostIndexRow;
import com.celfit.was.monitoring.BrandReadRepository.LatestSnapshotRow;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import com.celfit.was.v1.monitoring.TrackingItemAssembler;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * /v1/brand-monitoring/influencers 표면 계약(스펙 §6-6, 2026-08-27 인플루언서 집계 API) — 다계정
 * 병합·교차 중복 제거, 소유권 403, 파라미터 400, 필터·정렬·페이지 계약을 고정한다.
 *
 * <p>어셈블러({@link BrandPostAssembler})는 실 빈으로 붙이고 DB 접점만 mock한다 — 인덱스 조립부터
 * 집계·정렬까지 실제 코드가 돈다. 시드는 슬림 인덱스 행({@link BrandPostIndexRow})과 최신 지표
 * 프로젝션({@link LatestSnapshotRow}) 두 산지뿐이라, 게시물 목록 테스트와 달리 풀 카드 시드를
 * 깔지 않는다(이 표면은 하이드레이트를 아예 타지 않는다).
 */
@WebMvcTest(controllers = V1BrandInfluencersController.class,
		properties = {"was.cors.allowed-origins=http://localhost:3000", "monitoring.enabled=true"})
@Import({BrandPostAssembler.class, V1ExceptionAdvice.class, SecurityConfig.class})
class V1BrandInfluencersControllerTest {

	private static final String URL = "/v1/brand-monitoring/influencers";

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	BrandLinkRepository linkRepository;
	@MockitoBean
	BrandReadRepository brandReadRepository;
	@MockitoBean
	BrandPostCampaignRepository postCampaignRepository;
	/** 과도기 폴백(이관 전 레거시 direct) 전용 — 기본값이 빈 목록이라 이 테스트에선 자연히 no-op. */
	@MockitoBean
	BrandDirectPostRepository directPostRepository;
	/** 과도기 폴백 전용. */
	@MockitoBean
	TrackingItemAssembler trackingItemAssembler;
	@MockitoBean
	MonitoringItemRepository monitoringItemRepository;
	@MockitoBean
	Clock clock;

	@BeforeEach
	void fixedClock() {
		// 링크 창 컷의 기준 시각 고정 — 고정하지 않으면 2026-08-xx 시드가 시간이 지나며 창 밖으로
		// 밀려 테스트가 시한부가 된다. KST 2026-08-08 21:00.
		given(clock.instant()).willReturn(Instant.parse("2026-08-08T12:00:00Z"));
	}

	// ---------- 병합·집계 ----------

	@Test
	void 두_계정_게시물이_병합되고_같은_username은_한_행으로_합산된다() throws Exception {
		givenLinks(link(100L, 12), link(200L, 12));
		givenAccount(100L);
		givenAccount(200L);
		givenIndex(100L,
				indexRow("AAA", "2026-08-05T00:00:00Z", "glowdeep_92", "글로우딥", 10_000L, true),
				indexRow("BBB", "2026-08-04T00:00:00Z", "glowdeep_92", "글로우딥", 10_000L, true));
		givenIndex(200L,
				indexRow("CCC", "2026-08-02T00:00:00Z", "beautykim", "뷰티킴", 5_000L, false));
		givenMetrics(100L, metrics("AAA", 100L, 10L), metrics("BBB", 200L, 20L));
		givenMetrics(200L, metrics("CCC", 30L, 3L));

		mockMvc.perform(get(URL).param("accountIds", "100,200").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[0].username").value("glowdeep_92"))
				.andExpect(jsonPath("$.data[0].postCount").value(2))
				.andExpect(jsonPath("$.data[0].views").value(300))
				.andExpect(jsonPath("$.data[0].likes").value(30))
				.andExpect(jsonPath("$.data[0].sponsoredCount").value(2))
				.andExpect(jsonPath("$.data[0].followers").value(10000))
				.andExpect(jsonPath("$.data[0].profileUrl").value("https://www.instagram.com/glowdeep_92/"))
				.andExpect(jsonPath("$.data[0].latestPostAt").value("2026-08-05T09:00:00+09:00"))
				.andExpect(jsonPath("$.data[1].username").value("beautykim"))
				.andExpect(jsonPath("$.data[1].postCount").value(1))
				.andExpect(jsonPath("$.data[1].views").value(30))
				.andExpect(jsonPath("$.meta.total").value(2));
	}

	@Test
	void 두_계정을_태그한_같은_게시물은_한_번만_집계된다() throws Exception {
		givenLinks(link(100L, 12), link(200L, 12));
		givenAccount(100L);
		givenAccount(200L);
		givenIndex(100L, indexRow("SHARED", "2026-08-05T00:00:00Z", "beautykim", "뷰티킴", 5_000L, false));
		givenIndex(200L, indexRow("SHARED", "2026-08-05T00:00:00Z", "beautykim", "뷰티킴", 5_000L, false));
		givenMetrics(100L, metrics("SHARED", 50L, 5L));
		// 두 번 세면 여기 999가 더해져 조회수가 두 배로 접힌다 — 먼저 온 계정(100) 값만 남아야 한다.
		givenMetrics(200L, metrics("SHARED", 999L, 99L));

		mockMvc.perform(get(URL).param("accountIds", "100,200").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].postCount").value(1))
				.andExpect(jsonPath("$.data[0].views").value(50))
				.andExpect(jsonPath("$.data[0].likes").value(5));
	}

	@Test
	void monitoring_계정_행이_없는_브랜드는_건너뛴다() throws Exception {
		givenLinks(link(100L, 12), link(200L, 12));
		givenAccount(100L);
		given(brandReadRepository.findAccount(200L)).willReturn(Optional.empty());
		givenIndex(100L, indexRow("AAA", "2026-08-05T00:00:00Z", "glowdeep_92", "글로우딥", 10_000L, true));
		givenMetrics(100L, metrics("AAA", 100L, 10L));

		mockMvc.perform(get(URL).param("accountIds", "100,200").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].username").value("glowdeep_92"));
	}

	@Test
	void 계정별_표시_창_밖_게시물은_모수에서_빠진다() throws Exception {
		// collection_months=1 링크 — KST 2026-08-08 기준 창 하한은 2026-07-08이다.
		givenLinks(link(300L, 1));
		givenAccount(300L);
		givenIndex(300L,
				indexRow("IN", "2026-08-05T00:00:00Z", "glowdeep_92", "글로우딥", 10_000L, true),
				indexRow("OUT", "2026-06-05T00:00:00Z", "oldtimer", "올드타이머", 8_000L, true));
		givenMetrics(300L, metrics("IN", 100L, 10L), metrics("OUT", 700L, 70L));

		mockMvc.perform(get(URL).param("accountIds", "300").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].username").value("glowdeep_92"));
	}

	@Test
	void 최신_스냅샷이_없는_게시물은_postCount에만_기여한다() throws Exception {
		givenLinks(link(100L, 12));
		givenAccount(100L);
		givenIndex(100L,
				indexRow("AAA", "2026-08-05T00:00:00Z", "glowdeep_92", "글로우딥", 10_000L, true),
				indexRow("NOSNAP", "2026-08-04T00:00:00Z", "glowdeep_92", "글로우딥", 10_000L, true));
		givenMetrics(100L, metrics("AAA", 100L, 10L));

		mockMvc.perform(get(URL).param("accountIds", "100").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].postCount").value(2))
				.andExpect(jsonPath("$.data[0].views").value(100))
				.andExpect(jsonPath("$.data[0].likes").value(10))
				.andExpect(jsonPath("$.data[0].likesKnownCount").value(1));
	}

	@Test
	void 피드_게시물의_조회수는_합산되지_않는다() throws Exception {
		givenLinks(link(100L, 12));
		givenAccount(100L);
		givenIndex(100L,
				indexRow("REEL", "2026-08-05T00:00:00Z", "glowdeep_92", "글로우딥", 10_000L, true),
				indexRow("FEED", "2026-08-04T00:00:00Z", "glowdeep_92", "글로우딥", 10_000L, true));
		givenMetrics(100L, metrics("REEL", 100L, 10L),
				// 피드 스냅샷에도 views 값이 실려 있지만 서빙 규칙상 null로 접는다(항상 NULL 규칙).
				new LatestSnapshotRow("FEED", LocalDate.parse("2026-08-05"), "FEED", 555L, 5L, false, 2L));

		mockMvc.perform(get(URL).param("accountIds", "100").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].views").value(100))
				.andExpect(jsonPath("$.data[0].likes").value(15));
	}

	// ---------- 소유권 ----------

	@Test
	void 내_링크가_아닌_accountId가_섞이면_403이다() throws Exception {
		givenLinks(link(100L, 12));
		givenAccount(100L);

		mockMvc.perform(get(URL).param("accountIds", "100,999").with(user(principal())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	// ---------- 파라미터 검증 ----------

	@Test
	void accountIds가_없으면_400이다() throws Exception {
		mockMvc.perform(get(URL).with(user(principal())))
				.andExpect(status().isBadRequest());
	}

	@Test
	void accountIds가_비어_있으면_400이다() throws Exception {
		mockMvc.perform(get(URL).param("accountIds", "").with(user(principal())))
				.andExpect(status().isBadRequest());
	}

	@Test
	void accountIds에_비숫자가_섞이면_400이다() throws Exception {
		mockMvc.perform(get(URL).param("accountIds", "100,abc").with(user(principal())))
				.andExpect(status().isBadRequest());
	}

	@Test
	void accountIds에_빈_토큰이_섞이면_400이다() throws Exception {
		mockMvc.perform(get(URL).param("accountIds", "100,").with(user(principal())))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 모르는_sort_토큰은_400이다() throws Exception {
		givenLinks(link(100L, 12));
		givenAccount(100L);

		mockMvc.perform(get(URL).param("accountIds", "100").param("sort", "nope").with(user(principal())))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 모르는_sponsorship_값은_400이다() throws Exception {
		givenLinks(link(100L, 12));
		givenAccount(100L);

		mockMvc.perform(get(URL).param("accountIds", "100").param("sponsorship", "nope").with(user(principal())))
				.andExpect(status().isBadRequest());
	}

	@Test
	void limit이_범위를_벗어나면_400이다() throws Exception {
		givenLinks(link(100L, 12));
		givenAccount(100L);

		mockMvc.perform(get(URL).param("accountIds", "100").param("limit", "0").with(user(principal())))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get(URL).param("accountIds", "100").param("limit", "101").with(user(principal())))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get(URL).param("accountIds", "100").param("offset", "-1").with(user(principal())))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 모르는_follower_토큰은_400이다() throws Exception {
		givenLinks(link(100L, 12));
		givenAccount(100L);

		mockMvc.perform(get(URL).param("accountIds", "100").param("follower", "9k").with(user(principal())))
				.andExpect(status().isBadRequest());
	}

	// ---------- 필터 ----------

	@Test
	void keyword는_계정명과_닉네임_부분_일치로_거른다() throws Exception {
		givenThreeInfluencers();

		mockMvc.perform(get(URL).param("accountIds", "100").param("keyword", "GLOW").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].username").value("glowdeep_92"));
	}

	@Test
	void follower_구간_필터는_구간_밖_계정을_뺀다() throws Exception {
		givenThreeInfluencers();

		mockMvc.perform(get(URL).param("accountIds", "100").param("follower", "3k-10k").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].username").value("beautykim"));
	}

	@Test
	void sponsorship_필터는_협찬_이력_유무로_거른다() throws Exception {
		givenThreeInfluencers();

		mockMvc.perform(get(URL).param("accountIds", "100").param("sponsorship", "sponsored")
						.with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].username").value("glowdeep_92"));
		mockMvc.perform(get(URL).param("accountIds", "100").param("sponsorship", "organic")
						.with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[*].username").value(
						org.hamcrest.Matchers.containsInAnyOrder("beautykim", "skinlog")));
	}

	@Test
	void 업로드_기간_필터는_기간_밖_게시물을_모수에서_뺀다() throws Exception {
		givenThreeInfluencers();

		// beautykim은 08-04, skinlog는 08-03이라 from=2026-08-04면 skinlog만 빠진다.
		mockMvc.perform(get(URL).param("accountIds", "100").param("uploadedFrom", "2026-08-04")
						.with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[*].username").value(
						org.hamcrest.Matchers.containsInAnyOrder("glowdeep_92", "beautykim")));
	}

	// ---------- 정렬·페이지 ----------

	@Test
	void sort_likes는_좋아요를_못_센_계정을_맨_뒤로_보낸다() throws Exception {
		givenLinks(link(100L, 12));
		givenAccount(100L);
		givenIndex(100L,
				indexRow("A", "2026-08-05T00:00:00Z", "glowdeep_92", "글로우딥", 10_000L, true),
				indexRow("B", "2026-08-04T00:00:00Z", "beautykim", "뷰티킴", 5_000L, false),
				indexRow("C", "2026-08-03T00:00:00Z", "skinlog", "스킨로그", 60_000L, false));
		givenMetrics(100L, metrics("A", 100L, 7L),
				// 좋아요 숨김 = "모름" — 0(beautykim)보다도 뒤여야 한다.
				new LatestSnapshotRow("B", LocalDate.parse("2026-08-05"), "REELS", 200L, null, true, 2L),
				metrics("C", 300L, 0L));

		mockMvc.perform(get(URL).param("accountIds", "100").param("sort", "likes").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].username").value("glowdeep_92"))
				.andExpect(jsonPath("$.data[1].username").value("skinlog"))
				.andExpect(jsonPath("$.data[2].username").value("beautykim"))
				.andExpect(jsonPath("$.data[2].likesKnownCount").value(0));
	}

	@Test
	void sort_followers는_팔로워_내림차순이다() throws Exception {
		givenThreeInfluencers();

		mockMvc.perform(get(URL).param("accountIds", "100").param("sort", "followers").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].username").value("skinlog"))
				.andExpect(jsonPath("$.data[1].username").value("glowdeep_92"))
				.andExpect(jsonPath("$.data[2].username").value("beautykim"));
	}

	@Test
	void offset_limit을_생략하면_전량이고_meta_limit은_null이다() throws Exception {
		givenThreeInfluencers();

		mockMvc.perform(get(URL).param("accountIds", "100").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(3))
				.andExpect(jsonPath("$.meta.total").value(3))
				.andExpect(jsonPath("$.meta.offset").value(0))
				.andExpect(jsonPath("$.meta.limit").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	void offset_limit_페이지는_필터_후_전량을_기준으로_자른다() throws Exception {
		givenThreeInfluencers();

		mockMvc.perform(get(URL).param("accountIds", "100").param("sort", "followers")
						.param("offset", "1").param("limit", "1").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].username").value("glowdeep_92"))
				.andExpect(jsonPath("$.meta.total").value(3))
				.andExpect(jsonPath("$.meta.offset").value(1))
				.andExpect(jsonPath("$.meta.limit").value(1));
	}

	// ---------- 시드 ----------

	/** 팔로워·협찬·업로드일이 전부 갈리는 3인 시드 — 필터·정렬 단언이 실값으로 갈리게 한다. */
	private void givenThreeInfluencers() {
		givenLinks(link(100L, 12));
		givenAccount(100L);
		givenIndex(100L,
				indexRow("A", "2026-08-05T00:00:00Z", "glowdeep_92", "글로우딥", 10_000L, true),
				indexRow("B", "2026-08-04T00:00:00Z", "beautykim", "뷰티킴", 5_000L, false),
				indexRow("C", "2026-08-03T00:00:00Z", "skinlog", "스킨로그", 60_000L, false));
		givenMetrics(100L, metrics("A", 100L, 10L), metrics("B", 200L, 20L), metrics("C", 300L, 30L));
	}

	private void givenLinks(BrandLinkRow... links) {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(links));
	}

	private void givenAccount(long brandId) {
		given(brandReadRepository.findAccount(brandId)).willReturn(Optional.of(account(brandId)));
	}

	private void givenIndex(long brandId, BrandPostIndexRow... rows) {
		given(brandReadRepository.findBrandPostIndex(eq(brandId), any(), anyBoolean(), any()))
				.willReturn(List.of(rows));
	}

	private void givenMetrics(long brandId, LatestSnapshotRow... rows) {
		given(brandReadRepository.findLatestSnapshotsForBrand(eq(brandId), any(), anyBoolean()))
				.willReturn(List.of(rows));
	}

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	private static BrandLinkRow link(long brandId, int collectionMonths) {
		return new BrandLinkRow(brandId, 7L, brandId, "brand" + brandId, BrandAccountType.OWN,
				collectionMonths, OffsetDateTime.parse("2026-08-01T00:00:00Z"), null);
	}

	private static BrandAccountRow account(long brandId) {
		return new BrandAccountRow(brandId, "brand" + brandId, LocalDate.of(2026, 8, 8),
				OffsetDateTime.parse("2026-08-07T18:00:00Z"), OffsetDateTime.parse("2026-08-01T00:00:00Z"),
				OffsetDateTime.parse("2026-08-01T01:00:00Z"), null, 30876L, 12L, 340L, null, "리즈다",
				"https://cdn/pic.jpg", true, null, "ACTIVE", null,
				12, OffsetDateTime.parse("2026-08-01T00:00:00Z"), false, null);
	}

	/** 태그 감지된 릴스 1행 — 게시자 조인은 해결된 상태다(폴백 조회를 타지 않는다). */
	private static BrandPostIndexRow indexRow(String code, String takenAt, String username, String fullName,
			Long followers, boolean paid) {
		OffsetDateTime detectedAt = OffsetDateTime.parse("2026-08-06T02:00:00Z");
		return new BrandPostIndexRow(code, OffsetDateTime.parse(takenAt), detectedAt, null, null,
				username, null, paid, false, "REELS", null, username, fullName,
				"https://cdn/" + username + ".jpg", null, followers);
	}

	private static LatestSnapshotRow metrics(String code, Long views, Long likes) {
		return new LatestSnapshotRow(code, LocalDate.parse("2026-08-05"), "REELS", views, likes, false, 1L);
	}
}
