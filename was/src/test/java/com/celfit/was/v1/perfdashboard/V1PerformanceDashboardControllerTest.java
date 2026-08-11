package com.celfit.was.v1.perfdashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import com.celfit.was.v1.common.V1ExceptionAdvice;
import com.celfit.was.v1.perfdashboard.PerformanceContentResponse.PerformanceItemResponse;
import com.celfit.was.v1.perfdashboard.PerformanceContentResponse.PerformancePostResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * /v1/performance-dashboard 표면 계약(스펙 §7-1) — 필터 값 공간, statusCounts의 기간 필터 무관성,
 * 단건 조회를 고정한다. 조립은 Task 9가 끝냈으므로({@link PerformanceContentAssemblerTest})
 * 어셈블러는 mock이고 여기서 도는 건 HTTP 표면과 메모리 필터뿐이다.
 */
@WebMvcTest(controllers = V1PerformanceDashboardController.class,
		properties = {"was.cors.allowed-origins=http://localhost:3000"})
@Import({V1ExceptionAdvice.class, SecurityConfig.class})
class V1PerformanceDashboardControllerTest {

	private static final String CONTENTS = "/v1/performance-dashboard/contents";

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	PerformanceContentAssembler assembler;

	@MockitoBean
	PerformanceComparisonAssembler comparisonAssembler;

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	/** 어셈블러 스텁 — 전량(필터 전)을 준다. 수집 시각은 KST 2026-08-08T03:00:00로 고정. */
	private void givenAssembled(PerformanceContentResponse... contents) {
		givenAssembled(Set.of(), contents);
	}

	/** 경쟁사 집합까지 주는 스텁(08-12) — accountType 필터의 판정 근거다. */
	private void givenAssembled(Set<String> competitorBrandAccountIds, PerformanceContentResponse... contents) {
		given(assembler.assemble(7L)).willReturn(new PerformanceContentAssembler.Assembled(
				List.of(contents), OffsetDateTime.parse("2026-08-07T18:00:00Z"), competitorBrandAccountIds));
	}

	// ---------- statusCounts ----------

	@Test
	void statusCounts는_업로드_기간_필터와_무관하다() throws Exception {
		givenAssembled(content("1", "tracking", "2026-08-06"), content("2", "tracking", "2026-08-01"));

		mockMvc.perform(get(CONTENTS + "?uploadedFrom=2026-08-05").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].item.id").value("1"))
				.andExpect(jsonPath("$.meta.total").value(1))
				// 250건 상한 철폐(08-10) — limit은 형태 호환용으로 남고 반환 건수와 같다.
				.andExpect(jsonPath("$.meta.limit").value(1))
				.andExpect(jsonPath("$.meta.lastCollectedAt").value("2026-08-08T03:00:00+09:00"))
				// 기간 필터는 statusCounts에 적용되지 않는다(스펙 §7-1) — 2건 그대로.
				.andExpect(jsonPath("$.meta.statusCounts.tracking").value(2));
	}

	@Test
	void 상태_7종_키가_항상_전부_존재한다() throws Exception {
		// 0건 + 수집 이력 없음(브랜드 연동 전 신규 유저) — 가장 빈 응답에서도 키셋이 온전해야 한다.
		given(assembler.assemble(7L))
				.willReturn(new PerformanceContentAssembler.Assembled(List.of(), null, Set.of()));

		mockMvc.perform(get(CONTENTS).with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(0))
				.andExpect(jsonPath("$.meta.total").value(0))
				.andExpect(jsonPath("$.meta.statusCounts.tracking").value(0))
				.andExpect(jsonPath("$.meta.statusCounts.collecting").value(0))
				.andExpect(jsonPath("$.meta.statusCounts.detecting").value(0))
				.andExpect(jsonPath("$.meta.statusCounts.not_uploaded").value(0))
				.andExpect(jsonPath("$.meta.statusCounts.ended").value(0))
				.andExpect(jsonPath("$.meta.statusCounts.hidden").value(0))
				.andExpect(jsonPath("$.meta.statusCounts.error").value(0))
				// 수집 이력이 없으면 명시적 null(계약 무결성 규칙 #1).
				.andExpect(jsonPath("$.meta", Matchers.hasKey("lastCollectedAt")))
				.andExpect(jsonPath("$.meta.lastCollectedAt").value(Matchers.nullValue()));
	}

	@Test
	void statusCounts는_status_필터를_자기_자신에게_적용하지_않는다() throws Exception {
		givenAssembled(content("1", "tracking", "2026-08-06"), content("2", "ended", "2026-08-05"));

		mockMvc.perform(get(CONTENTS + "?status=ended").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].item.status").value("ended"))
				// 상태 축 뱃지가 자기 필터로 0이 되면 탭 바를 못 쓴다(§6-1 counts와 같은 취지).
				.andExpect(jsonPath("$.meta.statusCounts.ended").value(1))
				.andExpect(jsonPath("$.meta.statusCounts.tracking").value(1));
	}

	@Test
	void statusCounts는_분류_필터는_적용한다() throws Exception {
		givenAssembled(
				content("1", "SC1", "tracking", "2026-08-06", "individual", "unknown", null, null),
				content("2", "SC2", "ended", "2026-08-06", "tagged", "unknown", null, "100"));

		mockMvc.perform(get(CONTENTS + "?source=tagged").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.meta.statusCounts.ended").value(1))
				.andExpect(jsonPath("$.meta.statusCounts.tracking").value(0));
	}

	@Test
	void statusCounts는_협찬_필터도_적용한다() throws Exception {
		// sponsorship은 카운트 키 축(상태)과 직교라 자기 0화가 없다 — 분류 범위 필터로 적용한다.
		givenAssembled(
				content("1", "SC1", "tracking", "2026-08-06", "individual", "sponsored", null, null),
				content("2", "SC2", "ended", "2026-08-05", "individual", "organic", null, null));

		mockMvc.perform(get(CONTENTS + "?sponsorship=sponsored").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meta.statusCounts.tracking").value(1))
				.andExpect(jsonPath("$.meta.statusCounts.ended").value(0));
	}

	@Test
	void post가_없는_항목은_기간_필터에서_빠지지만_statusCounts엔_남는다() throws Exception {
		givenAssembled(content("1", "tracking", "2026-08-06"), content("2", "collecting", null));

		mockMvc.perform(get(CONTENTS + "?uploadedFrom=2026-08-01&uploadedTo=2026-08-31")
						.with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].item.id").value("1"))
				.andExpect(jsonPath("$.meta.statusCounts.collecting").value(1));
	}

	// ---------- 필터 ----------

	@Test
	void campaignId_none은_캠페인_없는_콘텐츠만이다() throws Exception {
		givenAssembled(
				content("1", "SC1", "tracking", "2026-08-06", "individual", "unknown", "9", null),
				content("2", "SC2", "tracking", "2026-08-05", "individual", "unknown", null, null));

		mockMvc.perform(get(CONTENTS + "?campaignId=none").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].item.id").value("2"))
				.andExpect(jsonPath("$.data[0].item.campaignId").value(Matchers.nullValue()));
	}

	@Test
	void campaignId_지정은_그_캠페인만이다() throws Exception {
		givenAssembled(
				content("1", "SC1", "tracking", "2026-08-06", "individual", "unknown", "9", null),
				content("2", "SC2", "tracking", "2026-08-05", "individual", "unknown", "10", null));

		mockMvc.perform(get(CONTENTS + "?campaignId=10").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].item.campaignId").value("10"));
	}

	@Test
	void campaignId_all은_필터하지_않는다() throws Exception {
		givenAssembled(
				content("1", "SC1", "tracking", "2026-08-06", "individual", "unknown", "9", null),
				content("2", "SC2", "tracking", "2026-08-05", "individual", "unknown", null, null));

		mockMvc.perform(get(CONTENTS + "?campaignId=all").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2));
	}

	@Test
	void brandAccountId_필터는_tagged와_direct를_모두_잡는다() throws Exception {
		givenAssembled(
				content("1", "SC1", "tracking", "2026-08-06", "tagged", "unknown", null, "100"),
				content("2", "SC2", "tracking", "2026-08-05", "direct", "unknown", null, "100"),
				content("3", "SC3", "tracking", "2026-08-04", "individual", "unknown", null, null),
				content("4", "SC4", "tracking", "2026-08-03", "tagged", "unknown", null, "200"));

		mockMvc.perform(get(CONTENTS + "?brandAccountId=100").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[*].item.id").value(Matchers.contains("1", "2")));
	}

	@Test
	void source와_sponsorship_필터가_함께_걸린다() throws Exception {
		givenAssembled(
				content("1", "SC1", "tracking", "2026-08-06", "tagged", "sponsored", null, "100"),
				content("2", "SC2", "tracking", "2026-08-05", "tagged", "organic", null, "100"),
				content("3", "SC3", "tracking", "2026-08-04", "individual", "sponsored", null, null));

		mockMvc.perform(get(CONTENTS + "?source=tagged&sponsorship=sponsored").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].item.id").value("1"));
	}

	@Test
	void status_필터는_해당_상태만_남긴다() throws Exception {
		givenAssembled(content("1", "tracking", "2026-08-06"), content("2", "ended", "2026-08-05"));

		mockMvc.perform(get(CONTENTS + "?status=ended").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].item.status").value("ended"));
	}

	@Test
	void 필터_미지정과_all은_전량이다() throws Exception {
		givenAssembled(
				content("1", "SC1", "tracking", "2026-08-06", "tagged", "sponsored", "9", "100"),
				content("2", "SC2", "ended", "2026-08-05", "individual", "organic", null, null));

		mockMvc.perform(get(CONTENTS + "?source=all&sponsorship=all&status=all").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.meta.total").value(2));
	}

	// ---------- accountType(08-12) ----------

	/**
	 * accountType 판정용 3종 픽스처 — own 브랜드(10)·경쟁사(11)·브랜드 미귀속 개인 추적(null).
	 * 경쟁사 집합은 {@code {"11"}}이라 콘텐츠 11번만 경쟁사 소속이다.
	 */
	private void givenOwnCompetitorIndividual() {
		givenAssembled(Set.of("11"),
				content("1", "SC1", "tracking", "2026-08-06", "tagged", "unknown", null, "10"),
				content("11", "SC11", "tracking", "2026-08-05", "tagged", "unknown", null, "11"),
				content("3", "SC3", "tracking", "2026-08-04", "individual", "unknown", null, null));
	}

	@Test
	void contents_기본은_경쟁사만_제외하고_개인추적은_포함한다() throws Exception {
		givenOwnCompetitorIndividual();

		mockMvc.perform(get(CONTENTS).with(user(principal())))
				.andExpect(status().isOk())
				// 미지정 = 전량(3)도 아니고 "own 브랜드만"(1)도 아니다 — 경쟁사만 뺀 2건.
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[*].item.id").value(Matchers.contains("1", "3")))
				.andExpect(jsonPath("$.data[?(@.brandAccountId == '11')]").doesNotExist())
				// 분류 필터라 statusCounts 모수에도 적용된다(스펙 §5).
				.andExpect(jsonPath("$.meta.statusCounts.tracking").value(2));
	}

	@Test
	void contents_accountType_own은_미지정과_같다() throws Exception {
		givenOwnCompetitorIndividual();

		mockMvc.perform(get(CONTENTS + "?accountType=own").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[*].item.id").value(Matchers.contains("1", "3")));
	}

	@Test
	void contents_accountType_competitor는_경쟁사만_돌려준다() throws Exception {
		givenOwnCompetitorIndividual();

		mockMvc.perform(get(CONTENTS + "?accountType=competitor").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].brandAccountId").value("11"))
				.andExpect(jsonPath("$.meta.statusCounts.tracking").value(1));
	}

	@Test
	void contents_accountType_all은_전부_돌려주고_statusCounts_모수도_같다() throws Exception {
		givenOwnCompetitorIndividual();

		mockMvc.perform(get(CONTENTS + "?accountType=all").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(3))
				.andExpect(jsonPath("$.meta.statusCounts.tracking").value(3));
	}

	@Test
	void contents_값_공간_밖_accountType은_400이다() throws Exception {
		mockMvc.perform(get(CONTENTS + "?accountType=rival").with(user(principal())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(assembler).should(never()).assemble(anyLong());
	}

	// ---------- 검증 ----------

	@Test
	void 허용_밖_source는_400이고_조립하지_않는다() throws Exception {
		mockMvc.perform(get(CONTENTS + "?source=brand").with(user(principal())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		// 검증은 전량 조립(두 DB·SQL ~11회)보다 먼저다.
		then(assembler).should(never()).assemble(anyLong());
	}

	@Test
	void 허용_밖_status는_400이다() throws Exception {
		mockMvc.perform(get(CONTENTS + "?status=paused").with(user(principal())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 허용_밖_sponsorship은_400이다() throws Exception {
		mockMvc.perform(get(CONTENTS + "?sponsorship=paid").with(user(principal())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 잘못된_날짜_형식은_400이다() throws Exception {
		mockMvc.perform(get(CONTENTS + "?uploadedTo=2026-13-99").with(user(principal())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 인증이_없으면_401이다() throws Exception {
		mockMvc.perform(get(CONTENTS))
				.andExpect(status().isUnauthorized());
	}

	// ---------- 단건 ----------

	@Test
	void 단건은_canonicalPostId로_찾고_없으면_404다() throws Exception {
		givenAssembled(content("1", "SC1", "tracking", "2026-08-06", "tagged", "unknown", null, "100"));

		mockMvc.perform(get(CONTENTS + "/SC1").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.canonicalPostId").value("SC1"))
				.andExpect(jsonPath("$.data.item.id").value("1"))
				.andExpect(jsonPath("$.data.source").value("tagged"));

		mockMvc.perform(get(CONTENTS + "/ZZZ").with(user(principal())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void 같은_shortcode가_둘이면_첫_매치를_돌려준다() throws Exception {
		givenAssembled(
				content("11", "DUP", "tracking", "2026-08-06", "individual", "unknown", "9", null),
				content("12", "DUP", "ended", "2026-08-06", "individual", "unknown", null, null));

		mockMvc.perform(get(CONTENTS + "/DUP").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.item.id").value("11"));
	}

	@Test
	void 단건은_필터를_받지_않고_post_없는_항목과_섞이지_않는다() throws Exception {
		givenAssembled(content("1", "collecting", null), content("2", "SC2", "ended", "2026-08-05",
				"individual", "unknown", null, null));

		mockMvc.perform(get(CONTENTS + "/SC2?status=tracking").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.item.id").value("2"));
	}

	// ---------- comparison ----------

	private static final String COMPARISON = "/v1/performance-dashboard/comparison";

	@Test
	void comparison은_분류_필터를_걸어_비교_어셈블러에_넘긴다() throws Exception {
		givenAssembled(
				content("1", "SC1", "tracking", "2026-08-06", "individual", "unknown", null, null),
				content("2", "SC2", "tracking", "2026-08-06", "tagged", "sponsored", null, "100"));
		given(comparisonAssembler.assemble(eq(7L), anyList())).willReturn(
				new PerformanceComparisonResponse(List.of(
						new PerformanceComparisonResponse.AccountComparison("100", "cclime.beauty", "own",
								"2026-05-14T09:12:00+09:00", List.of(
										new PerformanceComparisonResponse.Bucket("1w", true, 1,
												null, 5L, 2L, 1000L, 1, 0, 0))))));

		mockMvc.perform(get(COMPARISON + "?source=tagged").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.accounts.length()").value(1))
				.andExpect(jsonPath("$.data.accounts[0].brandAccountId").value("100"))
				.andExpect(jsonPath("$.data.accounts[0].buckets[0].key").value("1w"))
				.andExpect(jsonPath("$.data.accounts[0].buckets[0].covered").value(true))
				// null 합은 키를 유지한 명시적 null이다(계약 무결성 #1 — FE 규칙 ③).
				.andExpect(jsonPath("$.data.accounts[0].buckets[0]", Matchers.hasKey("views")))
				.andExpect(jsonPath("$.data.accounts[0].buckets[0].views").value(Matchers.nullValue()));

		// source=tagged 필터가 비교 모수에 적용됐는지 — individual 1건이 걸러져 tagged만 남아야 한다.
		ArgumentCaptor<List<PerformanceContentResponse>> captor = ArgumentCaptor.captor();
		then(comparisonAssembler).should().assemble(eq(7L), captor.capture());
		assertThat(captor.getValue()).hasSize(1);
		assertThat(captor.getValue().get(0).source()).isEqualTo("tagged");
	}

	@Test
	void comparison은_허용_값_밖_필터에_400이다() throws Exception {
		mockMvc.perform(get(COMPARISON + "?source=banana").with(user(principal())))
				.andExpect(status().isBadRequest());
		then(comparisonAssembler).should(never()).assemble(anyLong(), anyList());
	}

	@Test
	void comparison은_campaignId_none을_캠페인_없음으로_거른다() throws Exception {
		givenAssembled(
				content("1", "SC1", "tracking", "2026-08-06", "tagged", "unknown", "c-1", "100"),
				content("2", "SC2", "tracking", "2026-08-06", "tagged", "unknown", null, "100"));
		given(comparisonAssembler.assemble(eq(7L), anyList()))
				.willReturn(new PerformanceComparisonResponse(List.of()));

		mockMvc.perform(get(COMPARISON + "?campaignId=none").with(user(principal())))
				.andExpect(status().isOk());

		ArgumentCaptor<List<PerformanceContentResponse>> captor = ArgumentCaptor.captor();
		then(comparisonAssembler).should().assemble(eq(7L), captor.capture());
		assertThat(captor.getValue()).hasSize(1);
		assertThat(captor.getValue().get(0).item().campaignId()).isNull();
	}

	@Test
	void comparison은_계정별_accountType을_내리고_경쟁사도_포함한다() throws Exception {
		givenOwnCompetitorIndividual();
		given(comparisonAssembler.assemble(eq(7L), anyList())).willReturn(
				new PerformanceComparisonResponse(List.of(
						new PerformanceComparisonResponse.AccountComparison("10", "cclime.beauty", "own",
								"2026-05-14T09:12:00+09:00", List.of()),
						new PerformanceComparisonResponse.AccountComparison("11", "laperi_kr", "competitor",
								"2026-06-01T09:00:00+09:00", List.of()))));

		mockMvc.perform(get(COMPARISON).with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.accounts.length()").value(2))
				.andExpect(jsonPath("$.data.accounts[0].accountType").value("own"))
				.andExpect(jsonPath("$.data.accounts[1].accountType").value("competitor"));

		// 비교 화면엔 accountType 필터가 없다 — 경쟁사 콘텐츠도 모수에 그대로 들어간다(스펙 §6).
		ArgumentCaptor<List<PerformanceContentResponse>> captor = ArgumentCaptor.captor();
		then(comparisonAssembler).should().assemble(eq(7L), captor.capture());
		assertThat(captor.getValue()).hasSize(3);
	}

	// ---------- 픽스처 ----------

	private static PerformanceContentResponse content(String id, String status, String uploadedAt) {
		return content(id, "SC" + id, status, uploadedAt, "individual", "unknown", null, null);
	}

	/** uploadedAt이 null이면 post 자체가 없는 콘텐츠(collecting·detecting·not_uploaded)다. */
	private static PerformanceContentResponse content(String id, String shortcode, String status,
			String uploadedAt, String source, String sponsorship, String campaignId, String brandAccountId) {
		String url = "https://www.instagram.com/reel/" + shortcode + "/";
		PerformancePostResponse post = uploadedAt == null ? null : new PerformancePostResponse(url, shortcode,
				"reels", uploadedAt, "캡션", List.of(), "https://cdn/thumb.jpg", null, List.of(), null, false,
				0L, List.of());
		PerformanceItemResponse item = new PerformanceItemResponse(id, "url", status, "glowdeep_92", "글로우딥",
				"https://cdn/author.jpg", 12345L, null, campaignId,
				campaignId == null ? null : "여름 캠페인", url, "2026-08-01", 30, null, post, null);
		return new PerformanceContentResponse(item, source, sponsorship, post == null ? null : shortcode,
				List.of(), brandAccountId);
	}
}
