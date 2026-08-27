package com.celfit.was.v1.perfdashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import com.celfit.was.v1.monitoring.TrackingItemResponse.SnapshotResponse;
import com.celfit.was.v1.perfdashboard.PerformanceContentAssembler.DashboardIndex;
import com.celfit.was.v1.perfdashboard.PerformanceContentAssembler.DashboardRef;
import com.celfit.was.v1.perfdashboard.PerformanceContentResponse.PerformanceItemResponse;
import com.celfit.was.v1.perfdashboard.PerformanceContentResponse.PerformancePostResponse;
import com.celfit.was.v1.perfdashboard.PerformanceContentResponse.PreviousDayValues;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
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

	/** 단건 라우트 스텁 — 전량(필터 전)을 준다. 수집 시각은 KST 2026-08-08T03:00:00로 고정. */
	private void givenAssembled(PerformanceContentResponse... contents) {
		lenient().when(assembler.assemble(7L)).thenReturn(new PerformanceContentAssembler.Assembled(
				List.of(contents), OffsetDateTime.parse("2026-08-07T18:00:00Z"), Set.of()));
	}

	/** ref·카드 쌍 스텁 — 경쟁사 집합 없이(=own·개인추적만) 목록 라우트를 스텁한다. */
	private void givenIndexed(PerformanceContentResponse... contents) {
		givenIndexed(Set.of(), contents);
	}

	/**
	 * ref·카드 쌍 스텁(목록 라우트) — {@code index()}가 refs를, {@code hydratePage()}가 넘어온 ref
	 * 순서대로 대응 카드를 준다. 카드에서 ref를 유도하는 규칙은 어셈블러의 {@code refOf}와 같다
	 * ({@link #refOf}) — 컨트롤러가 ref 위에서 세고 거르고 정렬한 결과가 카드 기준 기대값과 일치해야
	 * 한다는 것 자체가 이 스텁의 계약이다.
	 *
	 * <p>경쟁사 집합(08-12)은 accountType 필터의 판정 근거다. lenient인 이유: 400으로 끝나는
	 * 테스트는 어느 스텁도 타지 않는다.
	 */
	private void givenIndexed(Set<String> competitorBrandAccountIds, PerformanceContentResponse... contents) {
		DashboardIndex index = index(OffsetDateTime.parse("2026-08-07T18:00:00Z"), competitorBrandAccountIds,
				Arrays.stream(contents).map(V1PerformanceDashboardControllerTest::refOf).toList(),
				Arrays.stream(contents).collect(Collectors.toMap(c -> c.item().id(), Function.identity(),
						(a, b) -> a, LinkedHashMap::new)));
		lenient().when(assembler.index(7L)).thenReturn(index);
		lenient().when(assembler.hydratePage(eq(index), anyList())).thenAnswer(invocation -> {
			List<DashboardRef> page = invocation.getArgument(1);
			return page.stream().map(r -> index.legacyCards().get(r.contentKey())).toList();
		});
	}

	/**
	 * 비교 라우트 스텁 — /comparison은 인덱스 패스(경량 ref)만 소비한다. 하이드레이트 재료
	 * (legacyCards·brandByCode·brandsById·campaignsById)는 이 표면과 무관해 비워 둔다.
	 */
	private void givenIndexedRefs(DashboardRef... refs) {
		givenIndexedRefs(Set.of(), refs);
	}

	/** 경쟁사 집합을 지정하는 ref 스텁 — accountType 함의(인플루언서 표면) 판정 근거다. */
	private void givenIndexedRefs(Set<String> competitorBrandAccountIds, DashboardRef... refs) {
		lenient().when(assembler.index(7L)).thenReturn(new DashboardIndex(
				7L, List.of(refs), OffsetDateTime.parse("2026-08-07T18:00:00Z"), competitorBrandAccountIds,
				Map.of(), Map.of(), Map.of(), Map.of()));
	}

	private static DashboardIndex index(OffsetDateTime lastCollectedAt, Set<String> competitorBrandAccountIds,
			List<DashboardRef> refs, Map<String, PerformanceContentResponse> cards) {
		return new DashboardIndex(7L, refs, lastCollectedAt, competitorBrandAccountIds, cards,
				Map.of(), Map.of(), Map.of());
	}

	/** 카드 → ref 유도(어셈블러 {@code refOf}와 같은 규칙) — 최신 스냅샷은 목록의 마지막 원소다. */
	private static DashboardRef refOf(PerformanceContentResponse content) {
		PerformancePostResponse post = content.item().post();
		SnapshotResponse latest = post == null || post.snapshots().isEmpty() ? null
				: post.snapshots().get(post.snapshots().size() - 1);
		return new DashboardRef(content.item().id(), content.canonicalPostId(), content.source(),
				content.sponsorship(), content.item().status(),
				PerformanceContentAssembler.uploadedOn(content), content.brandAccountId(),
				content.item().campaignId(), content.item().handle(), content.item().displayName(),
				content.item().profileImageUrl(), content.item().followers(),
				latest == null ? null : latest.views(), latest == null ? null : latest.likes(),
				latest != null && latest.likesHidden(), latest == null ? null : latest.comments(),
				latest != null);
	}

	// ---------- statusCounts ----------

	@Test
	void statusCounts는_업로드_기간_필터와_무관하다() throws Exception {
		givenIndexed(content("1", "tracking", "2026-08-06"), content("2", "tracking", "2026-08-01"));

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
		DashboardIndex empty = index(null, Set.of(), List.of(), Map.of());
		given(assembler.index(7L)).willReturn(empty);
		given(assembler.hydratePage(eq(empty), anyList())).willReturn(List.of());

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
		givenIndexed(content("1", "tracking", "2026-08-06"), content("2", "ended", "2026-08-05"));

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
		givenIndexed(
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
		givenIndexed(
				content("1", "SC1", "tracking", "2026-08-06", "individual", "sponsored", null, null),
				content("2", "SC2", "ended", "2026-08-05", "individual", "organic", null, null));

		mockMvc.perform(get(CONTENTS + "?sponsorship=sponsored").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meta.statusCounts.tracking").value(1))
				.andExpect(jsonPath("$.meta.statusCounts.ended").value(0));
	}

	@Test
	void post가_없는_항목은_기간_필터에서_빠지지만_statusCounts엔_남는다() throws Exception {
		givenIndexed(content("1", "tracking", "2026-08-06"), content("2", "collecting", null));

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
		givenIndexed(
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
		givenIndexed(
				content("1", "SC1", "tracking", "2026-08-06", "individual", "unknown", "9", null),
				content("2", "SC2", "tracking", "2026-08-05", "individual", "unknown", "10", null));

		mockMvc.perform(get(CONTENTS + "?campaignId=10").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].item.campaignId").value("10"))
				// 캠페인도 분류 필터라 statusCounts 모수에 적용된다(§7-1).
				.andExpect(jsonPath("$.meta.statusCounts.tracking").value(1));
	}

	@Test
	void campaignId_all은_필터하지_않는다() throws Exception {
		givenIndexed(
				content("1", "SC1", "tracking", "2026-08-06", "individual", "unknown", "9", null),
				content("2", "SC2", "tracking", "2026-08-05", "individual", "unknown", null, null));

		mockMvc.perform(get(CONTENTS + "?campaignId=all").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2));
	}

	@Test
	void brandAccountId_필터는_tagged와_direct를_모두_잡는다() throws Exception {
		givenIndexed(
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
		givenIndexed(
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
		givenIndexed(content("1", "tracking", "2026-08-06"), content("2", "ended", "2026-08-05"));

		mockMvc.perform(get(CONTENTS + "?status=ended").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].item.status").value("ended"));
	}

	@Test
	void 필터_미지정과_all은_전량이다() throws Exception {
		givenIndexed(
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
		givenIndexed(Set.of("11"),
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
	void contents_경쟁사_brandAccountId만_주면_그_브랜드_콘텐츠가_나온다() throws Exception {
		// brandAccountId 명시 = accountType=all 함의(08-12 리뷰). 함의가 없으면 기본값(경쟁사 제외)과
		// 서로를 상쇄해 오류도 힌트도 없이 빈 data + 전 상태 0이 돌아온다 — 브랜드 칩이 경쟁사까지
		// 내려주는데 /comparison은 같은 계정 막대를 그리므로 두 표면이 어긋나는 자리다.
		givenOwnCompetitorIndividual();

		mockMvc.perform(get(CONTENTS + "?brandAccountId=11").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].brandAccountId").value("11"))
				.andExpect(jsonPath("$.meta.statusCounts.tracking").value(1));
	}

	@Test
	void contents_brandAccountId에_accountType을_명시하면_명시값이_이긴다() throws Exception {
		// 함의는 미지정일 때만이다 — own을 명시했으면 "경쟁사 브랜드라 빈 결과"가 문자 그대로의 의미다.
		givenOwnCompetitorIndividual();

		mockMvc.perform(get(CONTENTS + "?brandAccountId=11&accountType=own").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(0))
				.andExpect(jsonPath("$.meta.statusCounts.tracking").value(0));
	}

	@Test
	void contents_brandAccountId_all은_함의를_켜지_않는다() throws Exception {
		// FE의 "전체" 탭이 brandAccountId=all로 넘어와도 브랜드를 집어 물은 게 아니다 — 기본값(경쟁사 제외) 유지.
		givenOwnCompetitorIndividual();

		mockMvc.perform(get(CONTENTS + "?brandAccountId=all").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[*].item.id").value(Matchers.contains("1", "3")));
	}

	@Test
	void contents_값_공간_밖_accountType은_400이다() throws Exception {
		mockMvc.perform(get(CONTENTS + "?accountType=rival").with(user(principal())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(assembler).should(never()).assemble(anyLong());
		then(assembler).should(never()).index(anyLong());
	}

	// ---------- 검증 ----------

	@Test
	void 허용_밖_source는_400이고_조립하지_않는다() throws Exception {
		mockMvc.perform(get(CONTENTS + "?source=brand").with(user(principal())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		// 검증은 인덱스 조립(두 DB·SQL 다회)보다 먼저다.
		then(assembler).should(never()).assemble(anyLong());
		then(assembler).should(never()).index(anyLong());
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

	// ---------- 조립 변형 라우팅(08-12 슬림 계약 → 08-27 2단 조립) ----------

	@Test
	void 목록과_비교는_전량_조립을_부르지_않는다() throws Exception {
		givenIndexed(content("1", "tracking", "2026-08-06"));
		given(comparisonAssembler.assemble(eq(7L), anyList()))
				.willReturn(new PerformanceComparisonResponse(List.of()));

		mockMvc.perform(get(CONTENTS).with(user(principal()))).andExpect(status().isOk());
		mockMvc.perform(get(COMPARISON).with(user(principal()))).andExpect(status().isOk());

		// 전량 풀 조립이 목록·비교 경로에 되살아나면 요청당 고정비가 재발한다(08-12·08-27 실측 근거).
		then(assembler).should(never()).assemble(anyLong());
		then(assembler).should(never()).assembleSlim(anyLong());
	}

	@Test
	void 단건은_전체_조립을_쓴다() throws Exception {
		givenAssembled(content("1", "SC1", "tracking", "2026-08-06", "tagged", "unknown", null, "100"));

		mockMvc.perform(get(CONTENTS + "/SC1").with(user(principal()))).andExpect(status().isOk());

		// 단건은 댓글 포함 계약(§7-1) — 목록의 2단 조립(댓글 없음)으로 바뀌면 상세 패널 댓글이 조용히 빈다.
		then(assembler).should(never()).index(anyLong());
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
		givenIndexedRefs(
				ref("1", "SC1", "tracking", "2026-08-06", "individual", "unknown", null, null),
				ref("2", "SC2", "tracking", "2026-08-06", "tagged", "sponsored", null, "100"));
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
		ArgumentCaptor<List<DashboardRef>> captor = ArgumentCaptor.captor();
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
		givenIndexedRefs(
				ref("1", "SC1", "tracking", "2026-08-06", "tagged", "unknown", "c-1", "100"),
				ref("2", "SC2", "tracking", "2026-08-06", "tagged", "unknown", null, "100"));
		given(comparisonAssembler.assemble(eq(7L), anyList()))
				.willReturn(new PerformanceComparisonResponse(List.of()));

		mockMvc.perform(get(COMPARISON + "?campaignId=none").with(user(principal())))
				.andExpect(status().isOk());

		ArgumentCaptor<List<DashboardRef>> captor = ArgumentCaptor.captor();
		then(comparisonAssembler).should().assemble(eq(7L), captor.capture());
		assertThat(captor.getValue()).hasSize(1);
		assertThat(captor.getValue().get(0).campaignId()).isNull();
	}

	@Test
	void comparison은_계정별_accountType을_내리고_경쟁사도_포함한다() throws Exception {
		// 경쟁사(11)·own(10)·individual 3건 — 비교 라우트는 accountType 필터가 없어 전부 모수다.
		givenIndexedRefs(
				ref("1", "SC1", "tracking", "2026-08-06", "tagged", "unknown", null, "10"),
				ref("11", "SC11", "tracking", "2026-08-05", "tagged", "unknown", null, "11"),
				ref("3", "SC3", "tracking", "2026-08-04", "individual", "unknown", null, null));
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
		ArgumentCaptor<List<DashboardRef>> captor = ArgumentCaptor.captor();
		then(comparisonAssembler).should().assemble(eq(7L), captor.capture());
		assertThat(captor.getValue()).hasSize(3);
	}

	// ---------- 정렬(2026-08-27 §2) ----------

	@Test
	void 정렬_views_desc는_최신_스냅샷_조회수_내림차순이고_null은_마지막이다() throws Exception {
		givenIndexed(contentWithViews("1", 100L), contentWithViews("2", null), contentWithViews("3", 300L));

		mockMvc.perform(get(CONTENTS + "?sort=views").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].item.id").value("3"))
				.andExpect(jsonPath("$.data[1].item.id").value("1"))
				.andExpect(jsonPath("$.data[2].item.id").value("2"));
	}

	@Test
	void 정렬_asc여도_null은_마지막이다() throws Exception {
		// null은 "값이 작은 것"이 아니라 "순위 밖"이다 — order를 뒤집어도 앞으로 올라오지 않는다.
		givenIndexed(contentWithViews("1", 100L), contentWithViews("2", null), contentWithViews("3", 300L));

		mockMvc.perform(get(CONTENTS + "?sort=views&order=asc").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].item.id").value("1"))
				.andExpect(jsonPath("$.data[1].item.id").value("3"))
				.andExpect(jsonPath("$.data[2].item.id").value("2"));
	}

	@Test
	void 정렬_likes는_좋아요_숨김을_순위에서_뺀다() throws Exception {
		// 숨김은 0이 아니라 미상이다 — 큰 값으로도 작은 값으로도 취급하면 안 된다.
		givenIndexed(metricContent("1", "2026-08-06", 100L, 10L, 5L, false, 3L),
				metricContent("2", "2026-08-06", 100L, 10L, 999L, true, 3L),
				metricContent("3", "2026-08-06", 100L, 10L, 50L, false, 3L));

		mockMvc.perform(get(CONTENTS + "?sort=likes").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].item.id").value(Matchers.contains("3", "1", "2")));
	}

	@Test
	void engagement는_좋아요_숨김이나_팔로워_미상이면_순위에서_빠진다() throws Exception {
		givenIndexed(
				metricContent("1", "2026-08-06", 100L, 10L, 10L, false, 5L),      // 0.15
				metricContent("2", "2026-08-06", 100L, 10L, 40L, true, 10L),      // 좋아요 숨김 → 제외
				metricContent("3", "2026-08-06", null, 10L, 40L, false, 10L),     // 팔로워 미상 → 제외
				metricContent("4", "2026-08-06", 1000L, 10L, 100L, false, 100L)); // 0.2

		mockMvc.perform(get(CONTENTS + "?sort=engagement").with(user(principal())))
				.andExpect(status().isOk())
				// 순위 안은 참여율 내림차순, 순위 밖(null) 둘은 마지막에서 업로드 최신순→id 타이브레이크.
				.andExpect(jsonPath("$.data[*].item.id").value(Matchers.contains("4", "1", "2", "3")));
	}

	@Test
	void 기본_정렬은_업로드_최신순이고_업로드일_미상은_마지막이다() throws Exception {
		// 기본값(uploaded desc)이 종전 전량 조립 순서와 같아야 페이지 미사용 FE의 응답이 안 바뀐다.
		givenIndexed(content("2", "tracking", "2026-08-01"), content("3", "collecting", null),
				content("1", "tracking", "2026-08-06"));

		mockMvc.perform(get(CONTENTS).with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].item.id").value(Matchers.contains("1", "2", "3")));
	}

	// ---------- 페이지네이션(2026-08-27 §2) ----------

	@Test
	void 페이지_두_쪽의_합은_전량이고_중복이_없다() throws Exception {
		givenIndexed(content("1", "tracking", "2026-08-06"), content("2", "tracking", "2026-08-05"),
				content("3", "tracking", "2026-08-04"));

		mockMvc.perform(get(CONTENTS + "?offset=0&limit=2").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].item.id").value(Matchers.contains("1", "2")))
				// total은 페이지가 아니라 필터 적용 후 전체다.
				.andExpect(jsonPath("$.meta.total").value(3))
				.andExpect(jsonPath("$.meta.page.offset").value(0))
				.andExpect(jsonPath("$.meta.page.limit").value(2))
				.andExpect(jsonPath("$.meta.statusCounts.tracking").value(3));

		mockMvc.perform(get(CONTENTS + "?offset=2&limit=2").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].item.id").value(Matchers.contains("3")))
				.andExpect(jsonPath("$.meta.total").value(3))
				.andExpect(jsonPath("$.meta.page.offset").value(2))
				.andExpect(jsonPath("$.meta.page.limit").value(2));
	}

	@Test
	void 페이지에_실린_ref만_하이드레이트_경계를_넘는다() throws Exception {
		// P0의 절감 계약 자체다 — 페이지 밖 콘텐츠의 카드 조립(스냅샷 시계열·표시 메타)이 되살아나면
		// 요청당 고정비가 데이터 규모에 다시 비례한다.
		givenIndexed(content("1", "tracking", "2026-08-06"), content("2", "tracking", "2026-08-05"),
				content("3", "tracking", "2026-08-04"));

		mockMvc.perform(get(CONTENTS + "?limit=1").with(user(principal()))).andExpect(status().isOk());

		ArgumentCaptor<List<DashboardRef>> captor = ArgumentCaptor.captor();
		then(assembler).should().hydratePage(any(), captor.capture());
		assertThat(captor.getValue()).extracting(DashboardRef::contentKey).containsExactly("1");
	}

	@Test
	void 페이지_파라미터_생략은_전량이고_meta_page는_0_null이다() throws Exception {
		givenIndexed(content("1", "tracking", "2026-08-06"), content("2", "tracking", "2026-08-05"));

		mockMvc.perform(get(CONTENTS).with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.meta.total").value(2))
				.andExpect(jsonPath("$.meta.limit").value(2))
				.andExpect(jsonPath("$.meta.page.offset").value(0))
				// limit null = 안 잘랐다는 표식(키는 유지 — 계약 무결성 규칙 #1).
				.andExpect(jsonPath("$.meta.page", Matchers.hasKey("limit")))
				.andExpect(jsonPath("$.meta.page.limit").value(Matchers.nullValue()));
	}

	@Test
	void limit_범위_밖은_400이다() throws Exception {
		for (String query : List.of("?limit=0", "?limit=101", "?offset=-1")) {
			mockMvc.perform(get(CONTENTS + query).with(user(principal())))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}
		then(assembler).should(never()).index(anyLong());
	}

	// ---------- 신규 필터(2026-08-27 §2) ----------

	@Test
	void accountIds는_쉼표_목록이고_brandAccountId보다_우선한다() throws Exception {
		// 경쟁사(15)를 섞어 둔다 — accountIds 명시도 brandAccountId와 같이 accountType=all을 함의한다.
		givenIndexed(Set.of("15"),
				content("1", "SC1", "tracking", "2026-08-06", "tagged", "unknown", null, "12"),
				content("2", "SC2", "tracking", "2026-08-05", "tagged", "unknown", null, "15"),
				content("3", "SC3", "tracking", "2026-08-04", "tagged", "unknown", null, "99"),
				content("4", "SC4", "tracking", "2026-08-03", "individual", "unknown", null, null));

		mockMvc.perform(get(CONTENTS + "?accountIds=12,15&brandAccountId=99").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].item.id").value(Matchers.contains("1", "2")))
				.andExpect(jsonPath("$.meta.statusCounts.tracking").value(2));
	}

	@Test
	void authorUsername은_그_작성자_게시물만이고_statusCounts_모수에도_적용된다() throws Exception {
		// 인플루언서 뷰의 상태 뱃지가 그 작성자 기준이어야 해서 분류 필터다(status·기간과 다르다).
		givenIndexed(authoredContent("1", "glowdeep_92", "tracking"),
				authoredContent("2", "otherhandle", "tracking"),
				authoredContent("3", "glowdeep_92", "ended"));

		mockMvc.perform(get(CONTENTS + "?authorUsername=GlowDeep_92").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].item.id").value(Matchers.contains("1", "3")))
				.andExpect(jsonPath("$.meta.statusCounts.tracking").value(1))
				.andExpect(jsonPath("$.meta.statusCounts.ended").value(1));
	}

	// ---------- snapshotMode(2026-08-27 §3) ----------

	@Test
	void snapshotMode_latest는_스냅샷을_최신_1개로_줄인다() throws Exception {
		givenIndexed(twoSnapshotContent("1"));

		mockMvc.perform(get(CONTENTS + "?snapshotMode=latest").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].item.post.snapshots.length()").value(1))
				.andExpect(jsonPath("$.data[0].item.post.snapshots[0].date").value("2026-08-06"))
				// 증가분 재료는 잘라내기 전 시계열에서 계산돼 있어 그대로 남는다(§3).
				.andExpect(jsonPath("$.data[0].item.post.previousDayValues.views").value(100));
	}

	@Test
	void snapshotMode_생략은_스냅샷_전체_이력이다() throws Exception {
		givenIndexed(twoSnapshotContent("1"));

		mockMvc.perform(get(CONTENTS).with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].item.post.snapshots.length()").value(2));
	}

	@Test
	void sort_order_snapshotMode_값_공간_밖은_400이다() throws Exception {
		for (String query : List.of("?sort=followers", "?order=random", "?snapshotMode=none")) {
			mockMvc.perform(get(CONTENTS + query).with(user(principal())))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}
		then(assembler).should(never()).index(anyLong());
	}

	// ---------- 인플루언서 집계(2026-08-27 §4) ----------

	private static final String INFLUENCERS = "/v1/performance-dashboard/influencers";

	@Test
	void influencers는_작성자별로_접고_기본_views_내림차순이다() throws Exception {
		// 입력 순서는 beautylover가 먼저다 — 기본 정렬(views desc)이 실제로 도는지 보려면 등장 순과
		// 기대 순서가 달라야 한다(집계기는 handle 등장 순을 유지한다).
		// beautylover는 좋아요·참여율이 더 높다 — 기본 키가 views임을 못 박는다(likes·engagement가
		// 기본값이면 순서가 뒤집힌다).
		givenIndexedRefs(
				influencerRef("1", "beautylover", "2026-08-02", "sponsored", "12", 1000L, 100L, 100L, 5L),
				influencerRef("2", "glowdeep_92", "2026-08-05", "sponsored", "12", 1000L, 200L, 20L, 7L),
				influencerRef("3", "glowdeep_92", "2026-08-06", "organic", "13", 1000L, 100L, 30L, 9L));

		mockMvc.perform(get(INFLUENCERS).with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].handle").value(Matchers.contains("glowdeep_92", "beautylover")))
				.andExpect(jsonPath("$.data[*].views").value(Matchers.contains(300, 100)))
				.andExpect(jsonPath("$.data[*].likes").value(Matchers.contains(50, 100)))
				// 집계기 배선 확인 — 행이 접히면서 건수·좋아요 모수·귀속 브랜드가 같이 실려야 한다.
				.andExpect(jsonPath("$.data[0].postCount").value(2))
				.andExpect(jsonPath("$.data[0].sponsoredCount").value(1))
				.andExpect(jsonPath("$.data[0].likesKnownCount").value(2))
				.andExpect(jsonPath("$.data[0].latestPostAt").value("2026-08-06"))
				.andExpect(jsonPath("$.data[0].brandAccountIds").value(Matchers.contains("12", "13")))
				.andExpect(jsonPath("$.meta.total").value(2))
				// 신설 표면이라 목록의 statusCounts·limit·lastCollectedAt은 없다(meta는 total·page뿐).
				.andExpect(jsonPath("$.meta", Matchers.aMapWithSize(2)))
				.andExpect(jsonPath("$.meta.page.offset").value(0));
	}

	@Test
	void influencers_필터는_집계_모수에_적용된다() throws Exception {
		// 목록의 statusCounts 같은 "필터 예외 모수"가 없다 — 필터가 걸리면 행 자체와 total이 같이 준다.
		givenIndexedRefs(
				withCampaign(influencerRef("1", "glowdeep_92", "2026-08-05", "sponsored", "12",
						1000L, 200L, 20L, 7L), "c-1"),
				influencerRef("2", "beautylover", "2026-08-02", "organic", "12", 1000L, 100L, 10L, 5L));

		mockMvc.perform(get(INFLUENCERS + "?sponsorship=sponsored").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].handle").value("glowdeep_92"))
				.andExpect(jsonPath("$.data[0].postCount").value(1))
				.andExpect(jsonPath("$.meta.total").value(1));

		mockMvc.perform(get(INFLUENCERS + "?campaignId=c-1").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].handle").value(Matchers.contains("glowdeep_92")))
				.andExpect(jsonPath("$.meta.total").value(1));

		// none은 값이 아니라 부재를 고르는 어휘다(목록·비교와 같은 술어).
		mockMvc.perform(get(INFLUENCERS + "?campaignId=none").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].handle").value(Matchers.contains("beautylover")))
				.andExpect(jsonPath("$.meta.total").value(1));
	}

	@Test
	void influencers_업로드_기간은_집계_모수를_좁히고_업로드일_미상은_빠진다() throws Exception {
		// 기간을 지정하면 업로드일 미상 ref는 판정 불가라 모수 밖이다(목록 data와 같은 규칙).
		givenIndexedRefs(
				influencerRef("1", "glowdeep_92", "2026-08-05", "sponsored", "12", 1000L, 200L, 20L, 7L),
				influencerRef("2", "glowdeep_92", "2026-08-01", "sponsored", "12", 1000L, 500L, 50L, 9L),
				influencerRef("3", "beautylover", null, "sponsored", "12", 1000L, 700L, 70L, 3L));

		mockMvc.perform(get(INFLUENCERS + "?uploadedFrom=2026-08-04").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].handle").value("glowdeep_92"))
				.andExpect(jsonPath("$.data[0].postCount").value(1))
				.andExpect(jsonPath("$.data[0].views").value(200))
				.andExpect(jsonPath("$.meta.total").value(1));
	}

	@Test
	void influencers_페이지는_정렬_후_슬라이스고_생략_시_전량이다() throws Exception {
		// cosmelog·beautylover는 views 동률이다 — 타이브레이크(handle 오름차순)까지 전순서여야
		// 페이지 간 중복·누락이 없다. 등장 순은 cosmelog가 먼저라 타이브레이크가 없으면 순서가 뒤집힌다.
		givenIndexedRefs(
				influencerRef("1", "cosmelog", "2026-08-02", "sponsored", "12", 1000L, 200L, 10L, 5L),
				influencerRef("2", "glowdeep_92", "2026-08-05", "sponsored", "12", 1000L, 300L, 20L, 7L),
				influencerRef("3", "beautylover", "2026-08-06", "sponsored", "12", 1000L, 200L, 30L, 9L));

		// 정렬(views desc: glowdeep_92 → beautylover → cosmelog) 후 슬라이스라 둘째 행이 나와야 한다.
		mockMvc.perform(get(INFLUENCERS + "?offset=1&limit=1").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].handle").value(Matchers.contains("beautylover")))
				// total은 페이지가 아니라 집계 행 전체다.
				.andExpect(jsonPath("$.meta.total").value(3))
				.andExpect(jsonPath("$.meta.page.offset").value(1))
				.andExpect(jsonPath("$.meta.page.limit").value(1));

		mockMvc.perform(get(INFLUENCERS).with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].handle")
						.value(Matchers.contains("glowdeep_92", "beautylover", "cosmelog")))
				.andExpect(jsonPath("$.meta.total").value(3))
				.andExpect(jsonPath("$.meta.page.offset").value(0))
				// limit null = 안 잘랐다는 표식(키는 유지 — 계약 무결성 규칙 #1).
				.andExpect(jsonPath("$.meta.page", Matchers.hasKey("limit")))
				.andExpect(jsonPath("$.meta.page.limit").value(Matchers.nullValue()));
	}

	@Test
	void influencers_engagement_정렬은_분모_미상을_마지막에_둔다() throws Exception {
		givenIndexedRefs(
				// 참여율 0.02 — 팔로워가 커서 낮다.
				influencerRef("1", "beautylover", "2026-08-02", "sponsored", "12", 1000L, 100L, 10L, 10L),
				// 팔로워 미상 → rated 분모 없음 → 순위 밖.
				influencerRef("2", "cosmelog", "2026-08-03", "sponsored", "12", null, 100L, 10L, 10L),
				// 참여율 0.2.
				influencerRef("3", "glowdeep_92", "2026-08-05", "sponsored", "12", 100L, 100L, 10L, 10L));

		mockMvc.perform(get(INFLUENCERS + "?sort=engagement").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].handle")
						.value(Matchers.contains("glowdeep_92", "beautylover", "cosmelog")));

		// null은 "작은 값"이 아니라 "순위 밖"이다 — order를 뒤집어도 앞으로 올라오지 않는다.
		mockMvc.perform(get(INFLUENCERS + "?sort=engagement&order=asc").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].handle")
						.value(Matchers.contains("beautylover", "glowdeep_92", "cosmelog")));
	}

	@Test
	void influencers_정렬_키_6종은_각자_다른_축을_본다() throws Exception {
		// 축마다 기대 순서가 전부 다른 픽스처다 — 키 하나가 다른 필드를 보면 즉시 어긋난다.
		givenIndexedRefs(
				influencerRef("1", "aromashop", "2026-08-01", "sponsored", "12", 1000L, 500L, 5L, 50L),
				influencerRef("2", "beautylover", "2026-08-05", "sponsored", "12", 1000L, 100L, 300L, 12L),
				influencerRef("3", "cosmelog", "2026-08-09", "sponsored", "12", 1000L, 200L, 20L, 5L),
				influencerRef("4", "cosmelog", "2026-08-04", "sponsored", "12", 1000L, 50L, 10L, 5L));

		Map<String, List<String>> expected = new LinkedHashMap<>();
		expected.put("views", List.of("aromashop", "cosmelog", "beautylover"));            // 500·250·100
		expected.put("likes", List.of("beautylover", "cosmelog", "aromashop"));            // 300·30·5
		expected.put("comments", List.of("aromashop", "beautylover", "cosmelog"));         // 50·12·10
		expected.put("posts", List.of("cosmelog", "aromashop", "beautylover"));            // 2·1·1(handle)
		expected.put("latest", List.of("cosmelog", "beautylover", "aromashop"));           // 08-09·08-05·08-01
		expected.put("engagement", List.of("beautylover", "aromashop", "cosmelog"));       // .312·.055·.02

		for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
			mockMvc.perform(get(INFLUENCERS + "?sort=" + entry.getKey()).with(user(principal())))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data[*].handle")
							.value(Matchers.contains(entry.getValue().toArray(String[]::new))));
		}
	}

	@Test
	void influencers_sort_값_공간_밖은_400이다() throws Exception {
		// uploaded는 목록 전용 키다 — 이 표면의 값 공간 밖이라 400이어야 한다(표면별 값 공간 분리).
		for (String query : List.of("?sort=followers", "?sort=uploaded", "?order=random")) {
			mockMvc.perform(get(INFLUENCERS + query).with(user(principal())))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}
		// 검증은 인덱스 패스보다 앞이다 — 400으로 끝날 요청이 DB를 건드리면 안 된다.
		then(assembler).should(never()).index(anyLong());
	}

	@Test
	void influencers_accountIds는_함의를_포함해_목록과_같은_규칙이다() throws Exception {
		givenIndexedRefs(Set.of("15"),
				influencerRef("1", "glowdeep_92", "2026-08-05", "sponsored", "12", 1000L, 200L, 20L, 7L),
				influencerRef("2", "rivalfan", "2026-08-06", "sponsored", "15", 1000L, 100L, 10L, 5L));

		// 경쟁사 브랜드를 콕 집어 물었으므로 accountType=all이 함의된다(겹쳐 걸면 조용히 빈다).
		mockMvc.perform(get(INFLUENCERS + "?accountIds=15").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].handle").value(Matchers.contains("rivalfan")))
				.andExpect(jsonPath("$.meta.total").value(1));

		// 미지정 기본값은 경쟁사 제외 — 함의가 진짜로 필요한 상황임을 같이 고정한다.
		mockMvc.perform(get(INFLUENCERS).with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].handle").value(Matchers.contains("glowdeep_92")));

		// 명시한 accountType이 함의를 이긴다(목록 §7-1과 같은 규칙).
		mockMvc.perform(get(INFLUENCERS + "?accountIds=15&accountType=own").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(0))
				.andExpect(jsonPath("$.meta.total").value(0));
	}

	// ---------- 픽스처 ----------

	/**
	 * 인플루언서 집계용 ref 픽스처 — 이 표면이 보는 축(작성자·협찬·브랜드·업로드일·지표)만 채운다.
	 * 스냅샷 있음·좋아요 숨김 아님 고정이라 지표가 그대로 합산 모수다(집계 규칙 자체는
	 * {@link PerformanceInfluencerAggregatorTest}가 고정한다).
	 */
	private static DashboardRef influencerRef(String id, String handle, String uploadedOn, String sponsorship,
			String brandAccountId, Long followers, Long views, Long likes, Long comments) {
		return new DashboardRef(id, "SC" + id, "tagged", sponsorship, "tracking",
				uploadedOn == null ? null : LocalDate.parse(uploadedOn), brandAccountId, null,
				handle, "표시:" + handle, "https://cdn/" + handle + ".jpg", followers,
				views, likes, false, comments, true);
	}

	/** 캠페인만 바꾼 ref 사본 — 파라미터를 하나 더 늘리는 대신 필요한 케이스에서만 감싼다. */
	private static DashboardRef withCampaign(DashboardRef ref, String campaignId) {
		return new DashboardRef(ref.contentKey(), ref.shortcode(), ref.source(), ref.sponsorship(), ref.status(),
				ref.uploadedOn(), ref.brandAccountId(), campaignId, ref.handle(), ref.displayName(),
				ref.profileImageUrl(), ref.followers(), ref.latestViews(), ref.latestLikes(),
				ref.latestLikesHidden(), ref.latestComments(), ref.hasSnapshots());
	}

	/** 지표 픽스처 — 최신 스냅샷 1개를 실은 콘텐츠(정렬 키의 산지). 업로드일·작성자 팔로워는 인자다. */
	private static PerformanceContentResponse metricContent(String id, String uploadedOn, Long followers,
			Long views, Long likes, boolean likesHidden, Long comments) {
		String shortcode = "SC" + id;
		String url = "https://www.instagram.com/reel/" + shortcode + "/";
		SnapshotResponse snapshot = new SnapshotResponse(uploadedOn, views, likes, likesHidden, comments,
				null, null, false, null);
		PerformancePostResponse post = new PerformancePostResponse(url, shortcode, "reels", uploadedOn, "캡션",
				List.of(), "https://cdn/thumb.jpg", null, List.of(snapshot), null, comments, false, 0L, List.of());
		PerformanceItemResponse item = new PerformanceItemResponse(id, "url", "tracking", "glowdeep_92", "글로우딥",
				"https://cdn/author.jpg", followers, null, null, null, url, "2026-08-01", 30, null, post, null);
		return new PerformanceContentResponse(item, "individual", "unknown", shortcode, List.of(), null);
	}

	/** views만 다른 지표 픽스처 — 나머지 정렬 키는 전부 같게 둔다. */
	private static PerformanceContentResponse contentWithViews(String id, Long views) {
		return metricContent(id, "2026-08-06", 1000L, views, 10L, false, 5L);
	}

	/** 작성자 핸들 픽스처(authorUsername 필터용) — 대시보드 handle은 소문자 계약이다. */
	private static PerformanceContentResponse authoredContent(String id, String handle, String status) {
		String shortcode = "SC" + id;
		String url = "https://www.instagram.com/reel/" + shortcode + "/";
		PerformancePostResponse post = new PerformancePostResponse(url, shortcode, "reels", "2026-08-06", "캡션",
				List.of(), "https://cdn/thumb.jpg", null, List.of(), null, null, false, 0L, List.of());
		PerformanceItemResponse item = new PerformanceItemResponse(id, "url", status, handle, "글로우딥",
				"https://cdn/author.jpg", 12345L, null, null, null, url, "2026-08-01", 30, null, post, null);
		return new PerformanceContentResponse(item, "individual", "unknown", shortcode, List.of(), null);
	}

	/** 스냅샷 2개 + previousDayValues를 실은 픽스처(snapshotMode 계약용) — 하이드레이트 카드의 셰이프다. */
	private static PerformanceContentResponse twoSnapshotContent(String id) {
		String shortcode = "SC" + id;
		String url = "https://www.instagram.com/reel/" + shortcode + "/";
		List<SnapshotResponse> snapshots = List.of(
				new SnapshotResponse("2026-08-05", 100L, 10L, false, 3L, null, null, false, null),
				new SnapshotResponse("2026-08-06", 150L, 14L, false, 5L, null, null, false, null));
		PerformancePostResponse post = new PerformancePostResponse(url, shortcode, "reels", "2026-08-05", "캡션",
				List.of(), "https://cdn/thumb.jpg", null, snapshots,
				new PreviousDayValues(100L, 10L, 3L), 5L, false, 0L, List.of());
		PerformanceItemResponse item = new PerformanceItemResponse(id, "url", "tracking", "glowdeep_92", "글로우딥",
				"https://cdn/author.jpg", 12345L, null, null, null, url, "2026-08-01", 30, null, post, null);
		return new PerformanceContentResponse(item, "individual", "unknown", shortcode, List.of(), null);
	}


	private static PerformanceContentResponse content(String id, String status, String uploadedAt) {
		return content(id, "SC" + id, status, uploadedAt, "individual", "unknown", null, null);
	}

	/**
	 * 인덱스 패스 ref 픽스처(비교 라우트용) — 이 표면이 보는 건 분류 필터 필드(source·sponsorship·
	 * campaignId)뿐이라 지표는 비워 둔다(집계 규칙은 {@link PerformanceComparisonAssemblerTest}).
	 * uploadedOn이 null이면 업로드일 미상(post 없는 collecting·detecting·not_uploaded).
	 */
	private static DashboardRef ref(String id, String shortcode, String status, String uploadedOn,
			String source, String sponsorship, String campaignId, String brandAccountId) {
		return new DashboardRef(id, shortcode, source, sponsorship, status,
				uploadedOn == null ? null : LocalDate.parse(uploadedOn), brandAccountId, campaignId,
				"glowdeep_92", "글로우딥", "https://cdn/author.jpg", 12345L, null, null, false, null, false);
	}

	/** uploadedAt이 null이면 post 자체가 없는 콘텐츠(collecting·detecting·not_uploaded)다. */
	private static PerformanceContentResponse content(String id, String shortcode, String status,
			String uploadedAt, String source, String sponsorship, String campaignId, String brandAccountId) {
		String url = "https://www.instagram.com/reel/" + shortcode + "/";
		PerformancePostResponse post = uploadedAt == null ? null : new PerformancePostResponse(url, shortcode,
				"reels", uploadedAt, "캡션", List.of(), "https://cdn/thumb.jpg", null, List.of(), null, null,
				false, 0L, List.of());
		PerformanceItemResponse item = new PerformanceItemResponse(id, "url", status, "glowdeep_92", "글로우딥",
				"https://cdn/author.jpg", 12345L, null, campaignId,
				campaignId == null ? null : "여름 캠페인", url, "2026-08-01", 30, null, post, null);
		return new PerformanceContentResponse(item, source, sponsorship, post == null ? null : shortcode,
				List.of(), brandAccountId);
	}
}
