package com.celfit.was.v1.perfdashboard;

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
import com.celfit.was.v1.common.V1ExceptionAdvice;
import com.celfit.was.v1.perfdashboard.PerformanceContentResponse.PerformanceItemResponse;
import com.celfit.was.v1.perfdashboard.PerformanceContentResponse.PerformancePostResponse;
import java.time.OffsetDateTime;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
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

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	/** 어셈블러 스텁 — 전량(필터 전)을 준다. 수집 시각은 KST 2026-08-08T03:00:00로 고정. */
	private void givenAssembled(PerformanceContentResponse... contents) {
		given(assembler.assemble(7L)).willReturn(new PerformanceContentAssembler.Assembled(
				List.of(contents), OffsetDateTime.parse("2026-08-07T18:00:00Z")));
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
				.willReturn(new PerformanceContentAssembler.Assembled(List.of(), null));

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
