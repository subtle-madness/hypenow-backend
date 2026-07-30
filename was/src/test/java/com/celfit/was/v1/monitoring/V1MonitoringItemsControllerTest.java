package com.celfit.was.v1.monitoring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.monitoring.CancelResult;
import com.celfit.was.monitoring.ExtendResult;
import com.celfit.was.monitoring.MonitoringCommandClient;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.monitoring.MonitoringUnavailableException;
import com.celfit.was.monitoring.TargetRow;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.celfit.was.monitoring.RegistrationRepository;

/**
 * /v1/monitoring/items 컨트롤러 슬라이스 — POST(6.27) 구조 검증 400 전종·201 정상(혼합 등록)·
 * campaign 동봉 조건·전체 실패 시 빈 items·nullable 키 명시적 존재, PATCH(6.29) 400 전종·404·
 * campaign 동봉·503, cancel(6.30) 400 전종·404·상태 전이·503을 검증한다. V1CampaignService는
 * 실 빈으로 붙여 resolveOrCreate 경로까지 통과시키고, DB·monitoring 접근은 리포지토리·클라이언트를
 * mock한다(V1CampaignControllerTest 관용구와 동일). MonitoringReadRepository·MonitoringCommandClient는
 * V1MonitoringItemUpdateService가 Optional로 받으므로, 여기서 mock 빈을 등록해 두면 Optional.of(mock)로
 * 주입된다(둘 다 등록하지 않아도 컨텍스트는 뜨지만 target 확정 행 케이스를 검증할 수 없다).
 */
@WebMvcTest(controllers = V1MonitoringItemsController.class, properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({V1MonitoringRegistrationService.class, V1MonitoringItemUpdateService.class, V1CampaignService.class,
		TrackingItemAssembler.class, V1ExceptionAdvice.class, SecurityConfig.class})
class V1MonitoringItemsControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	MonitoringItemRepository itemRepository;
	@MockitoBean
	RegistrationRepository registrationRepository;
	@MockitoBean
	CampaignRepository campaignRepository;
	@MockitoBean
	RegistrationExecutor executor;
	@MockitoBean
	MonitoringReadRepository readRepository;
	@MockitoBean
	MonitoringCommandClient commandClient;

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	@BeforeEach
	void 등록_요청_기본_스텁() {
		given(registrationRepository.insert(anyLong(), anyInt(), any())).willReturn(555L);
	}

	private static MonitoringItemRow accountItem(long id, Long targetId, LocalDate registeredOn) {
		return new MonitoringItemRow(id, 7L, "account", UUID.randomUUID(), targetId, null, "glowdeep", null,
				"{\"and\":[],\"or\":[],\"exclude\":[]}", 14, registeredOn, null, null, OffsetDateTime.now());
	}

	private static MonitoringItemRow urlItem(long id, Long targetId, LocalDate registeredOn) {
		return new MonitoringItemRow(id, 7L, "url", UUID.randomUUID(), targetId, null, "ABC123",
				"https://www.instagram.com/p/ABC123/", null, 14, registeredOn, null, null, OffsetDateTime.now());
	}

	private static MonitoringItemRow canceledItem(long id, String canceledFrom, LocalDate registeredOn) {
		return new MonitoringItemRow(id, 7L, "account", UUID.randomUUID(), null, null, "glowdeep", null,
				"{\"and\":[],\"or\":[],\"exclude\":[]}", 14, registeredOn, OffsetDateTime.now(), canceledFrom,
				OffsetDateTime.now());
	}

	private static TargetRow targetRow(String status, String username, String trackedShortCode) {
		return new TargetRow(900L, "ACCOUNT", username, null, null, status, trackedShortCode, null, "key",
				OffsetDateTime.now(), OffsetDateTime.now(), null, null, null, null, null, false, null);
	}

	@Test
	void posts_accounts_모두_비면_400() throws Exception {
		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"trackingDays":14}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(registrationRepository).should(never()).insert(anyLong(), anyInt(), any());
	}

	@Test
	void 합산_100개_초과면_400() throws Exception {
		StringBuilder posts = new StringBuilder("[");
		for (int i = 0; i < 101; i++) {
			if (i > 0) {
				posts.append(',');
			}
			posts.append("\"https://www.instagram.com/p/POST").append(i).append("/\"");
		}
		posts.append(']');

		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"posts\":" + posts + ",\"trackingDays\":14}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(registrationRepository).should(never()).insert(anyLong(), anyInt(), any());
	}

	@Test
	void trackingDays_없으면_400() throws Exception {
		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"posts":["https://www.instagram.com/p/ABC123/"]}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void trackingDays_0은_400() throws Exception {
		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"posts":["https://www.instagram.com/p/ABC123/"],"trackingDays":0}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void trackingDays_91은_400() throws Exception {
		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"posts":["https://www.instagram.com/p/ABC123/"],"trackingDays":91}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void trackingDays_경계값_1은_통과한다() throws Exception {
		given(itemRepository.insertPending(anyLong(), eq("url"), any(), any(), anyString(), anyString(), any(),
				eq(1), any())).willReturn(400L);

		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"posts":["https://www.instagram.com/p/ABC123/"],"trackingDays":1}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.items[0].trackingDays").value(1));
	}

	@Test
	void trackingDays_경계값_90은_통과한다() throws Exception {
		given(itemRepository.insertPending(anyLong(), eq("url"), any(), any(), anyString(), anyString(), any(),
				eq(90), any())).willReturn(401L);

		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"posts":["https://www.instagram.com/p/ABC123/"],"trackingDays":90}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.items[0].trackingDays").value(90));
	}

	@Test
	void trackingDays_비정수_문자열은_400() throws Exception {
		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"posts":["https://www.instagram.com/p/ABC123/"],"trackingDays":"14"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void trackingDays_소수는_400() throws Exception {
		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"posts":["https://www.instagram.com/p/ABC123/"],"trackingDays":14.5}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void accounts_있는데_keywords_누락이면_400() throws Exception {
		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"accounts":["glowdeep"],"trackingDays":14}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void accounts_있는데_and_or_합쳐_0개면_400() throws Exception {
		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"accounts":["glowdeep"],"trackingDays":14,
								 "keywords":{"and":[],"or":[],"exclude":["나눔"]}}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void keywords_배열당_5개_초과면_400() throws Exception {
		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"accounts":["glowdeep"],"trackingDays":14,
								 "keywords":{"and":["a","b","c","d","e","f"],"or":[],"exclude":[]}}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 게시물_전용_등록은_빈_keywords여도_통과한다() throws Exception {
		given(itemRepository.insertPending(anyLong(), eq("url"), any(), any(), anyString(), anyString(), any(),
				anyInt(), any())).willReturn(200L);

		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"posts":["https://www.instagram.com/p/ABC123/"],"trackingDays":14,
								 "keywords":{"and":[],"or":[],"exclude":[]}}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.items.length()").value(1));
	}

	@Test
	void keywords_배열_원소가_문자열이_아니면_400() throws Exception {
		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"accounts":["glowdeep"],"trackingDays":14,
								 "keywords":{"and":[1,2],"or":[],"exclude":[]}}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(registrationRepository).should(never()).insert(anyLong(), anyInt(), any());
	}

	@Test
	void campaignId_campaignName_동시_전달은_400() throws Exception {
		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"posts":["https://www.instagram.com/p/ABC123/"],"trackingDays":14,
								 "campaignId":"1","campaignName":"이름"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 존재하지_않는_campaignId는_404() throws Exception {
		given(campaignRepository.findByIdAndUser(999L, 7L)).willReturn(Optional.empty());

		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"posts":["https://www.instagram.com/p/ABC123/"],"trackingDays":14,"campaignId":"999"}"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void posts_accounts_혼합_등록은_201이고_items_순서를_보존한다() throws Exception {
		given(itemRepository.insertPending(anyLong(), eq("url"), any(), any(), anyString(), anyString(), any(),
				anyInt(), any())).willReturn(101L);
		given(itemRepository.insertPending(anyLong(), eq("account"), any(), any(), anyString(), isNull(), any(),
				anyInt(), any())).willReturn(102L);

		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"posts":["https://www.instagram.com/reel/POSTA/"],
								 "accounts":["GlowDeep"],
								 "keywords":{"and":["글로우딥"],"or":[],"exclude":[]},
								 "trackingDays":14}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.registrationId").value("555"))
				.andExpect(jsonPath("$.data.items.length()").value(2))
				.andExpect(jsonPath("$.data.items[0].id").value("101"))
				.andExpect(jsonPath("$.data.items[0].mode").value("url"))
				.andExpect(jsonPath("$.data.items[0].status").value("collecting"))
				.andExpect(jsonPath("$.data.items[1].id").value("102"))
				.andExpect(jsonPath("$.data.items[1].mode").value("account"))
				.andExpect(jsonPath("$.data.items[1].status").value("detecting"))
				.andExpect(jsonPath("$.data.items[1].handle").value("glowdeep"));
	}

	@Test
	void 전_항목_실패해도_201이고_items는_빈_배열이다() throws Exception {
		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"posts":["not-a-url"],"trackingDays":14}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.items.length()").value(0));

		then(registrationRepository).should().insert(7L, 14, null);
	}

	@Test
	void 신규로_생성된_캠페인만_응답에_동봉된다() throws Exception {
		given(campaignRepository.findByNameAndUser(eq("새 캠페인"), eq(7L))).willReturn(Optional.empty());
		given(campaignRepository.insert(eq(7L), eq("새 캠페인"), isNull(), isNull(), isNull(), isNull(), isNull()))
				.willReturn(new CampaignRow(50L, 7L, "새 캠페인", null, null, null, null, null,
						OffsetDateTime.parse("2026-07-30T00:00:00Z")));
		given(itemRepository.insertPending(anyLong(), eq("url"), any(), eq(50L), anyString(), anyString(), any(),
				anyInt(), any())).willReturn(200L);

		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"posts":["https://www.instagram.com/p/ABC123/"],"trackingDays":14,
								 "campaignName":"새 캠페인"}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.campaign.id").value("50"))
				.andExpect(jsonPath("$.data.campaign.name").value("새 캠페인"))
				.andExpect(jsonPath("$.data.items[0].campaignId").value("50"))
				.andExpect(jsonPath("$.data.items[0].campaignName").value("새 캠페인"));
	}

	@Test
	void 기존_캠페인에_연결되면_campaign을_동봉하지_않는다() throws Exception {
		given(campaignRepository.findByNameAndUser(eq("기존 캠페인"), eq(7L)))
				.willReturn(Optional.of(new CampaignRow(60L, 7L, "기존 캠페인", null, null, null, null, null,
						OffsetDateTime.parse("2026-07-01T00:00:00Z"))));
		given(itemRepository.insertPending(anyLong(), eq("url"), any(), eq(60L), anyString(), anyString(), any(),
				anyInt(), any())).willReturn(201L);

		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"posts":["https://www.instagram.com/p/ABC123/"],"trackingDays":14,
								 "campaignName":"기존 캠페인"}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data", Matchers.not(Matchers.hasKey("campaign"))))
				.andExpect(jsonPath("$.data.items[0].campaignId").value("60"));
	}

	@Test
	void post와_profileImageUrl은_null이어도_키_자체는_명시적으로_존재한다() throws Exception {
		given(itemRepository.insertPending(anyLong(), eq("url"), any(), any(), anyString(), anyString(), any(),
				anyInt(), any())).willReturn(300L);

		mockMvc.perform(post("/v1/monitoring/items").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"posts":["https://www.instagram.com/p/ABC123/"],"trackingDays":14}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.items[0]", Matchers.hasKey("post")))
				.andExpect(jsonPath("$.data.items[0].post").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.data.items[0]", Matchers.hasKey("profileImageUrl")))
				.andExpect(jsonPath("$.data.items[0].profileImageUrl").value(Matchers.nullValue()));
	}

	// ── PATCH /v1/monitoring/items/{itemId} (6.29) ─────────────────────────────

	@Test
	void PATCH_존재하지_않는_itemId는_404() throws Exception {
		given(itemRepository.findByIdAndUser(999L, 7L)).willReturn(Optional.empty());

		mockMvc.perform(patch("/v1/monitoring/items/999").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"trackingDays\":30}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void PATCH_숫자가_아닌_itemId도_404() throws Exception {
		mockMvc.perform(patch("/v1/monitoring/items/abc").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"trackingDays\":30}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void PATCH_trackingDays_0은_400() throws Exception {
		given(itemRepository.findByIdAndUser(10L, 7L))
				.willReturn(Optional.of(accountItem(10L, null, LocalDate.now(KstTimestamps.KST))));

		mockMvc.perform(patch("/v1/monitoring/items/10").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"trackingDays\":0}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void PATCH_trackingDays_91은_400() throws Exception {
		given(itemRepository.findByIdAndUser(11L, 7L))
				.willReturn(Optional.of(accountItem(11L, null, LocalDate.now(KstTimestamps.KST))));

		mockMvc.perform(patch("/v1/monitoring/items/11").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"trackingDays\":91}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void PATCH_변경후_종료일이_오늘_이후가_아니면_400() throws Exception {
		// registeredOn+10일 = 오늘-10일 — "당일 종료도 거부" 포함해 오늘 이후가 아닌 경우 전부 400.
		LocalDate registeredOn = LocalDate.now(KstTimestamps.KST).minusDays(20);
		given(itemRepository.findByIdAndUser(12L, 7L)).willReturn(Optional.of(accountItem(12L, null, registeredOn)));

		mockMvc.perform(patch("/v1/monitoring/items/12").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"trackingDays\":10}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(itemRepository).should(never()).updateTrackingDays(anyLong(), anyInt());
	}

	@Test
	void PATCH_종결_상태에서_trackingDays_변경은_400_현재_상태_안내() throws Exception {
		LocalDate registeredOn = LocalDate.now(KstTimestamps.KST);   // +30일 = 미래 — 종료일 검증은 통과, 상태만 걸린다.
		given(itemRepository.findByIdAndUser(13L, 7L))
				.willReturn(Optional.of(canceledItem(13L, "tracking", registeredOn)));   // canceled_from=tracking → 유도 status=ended

		mockMvc.perform(patch("/v1/monitoring/items/13").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"trackingDays\":30}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.error.message", Matchers.containsString("종료")));

		then(itemRepository).should(never()).updateTrackingDays(anyLong(), anyInt());
	}

	// ── 리뷰 반영(2026-07-30) 회귀 — pending 만료 유도가 붙은 뒤에도 6.29 진행 중 게이트가 정합한다.

	@Test
	void PATCH_만료된_pending_행은_not_uploaded로_유도돼_기간_변경_불가_400() throws Exception {
		// registeredOn(30일 전) + 기존 trackingDays(14) = 이미 만료 → 유도 status=not_uploaded(진행 중 아님).
		// 요청 trackingDays(90)는 그 자체로는 미래 종료일이라 "종료일 검증"은 통과하고 상태 게이트에서 막힌다.
		LocalDate registeredOn = LocalDate.now(KstTimestamps.KST).minusDays(30);
		given(itemRepository.findByIdAndUser(26L, 7L)).willReturn(Optional.of(accountItem(26L, null, registeredOn)));

		mockMvc.perform(patch("/v1/monitoring/items/26").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"trackingDays\":90}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.error.message", Matchers.containsString("미업로드")));

		then(itemRepository).should(never()).updateTrackingDays(anyLong(), anyInt());
	}

	@Test
	void PATCH_campaignId_campaignName_동시_전달은_400() throws Exception {
		given(itemRepository.findByIdAndUser(14L, 7L))
				.willReturn(Optional.of(urlItem(14L, null, LocalDate.now(KstTimestamps.KST))));

		mockMvc.perform(patch("/v1/monitoring/items/14").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"campaignId\":\"1\",\"campaignName\":\"이름\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void PATCH_존재하지_않는_campaignId는_404() throws Exception {
		given(itemRepository.findByIdAndUser(15L, 7L))
				.willReturn(Optional.of(urlItem(15L, null, LocalDate.now(KstTimestamps.KST))));
		given(campaignRepository.findByIdAndUser(999L, 7L)).willReturn(Optional.empty());

		mockMvc.perform(patch("/v1/monitoring/items/15").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"campaignId\":\"999\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void PATCH_campaignName으로_신규_생성되면_campaign이_동봉된다() throws Exception {
		given(itemRepository.findByIdAndUser(16L, 7L))
				.willReturn(Optional.of(urlItem(16L, null, LocalDate.now(KstTimestamps.KST))));
		given(campaignRepository.findByNameAndUser(eq("새 캠페인"), eq(7L))).willReturn(Optional.empty());
		given(campaignRepository.insert(eq(7L), eq("새 캠페인"), isNull(), isNull(), isNull(), isNull(), isNull()))
				.willReturn(new CampaignRow(50L, 7L, "새 캠페인", null, null, null, null, null,
						OffsetDateTime.parse("2026-07-30T00:00:00Z")));

		mockMvc.perform(patch("/v1/monitoring/items/16").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"campaignName\":\"새 캠페인\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.campaign.id").value("50"))
				.andExpect(jsonPath("$.data.campaign.name").value("새 캠페인"))
				.andExpect(jsonPath("$.data.campaignId").value("50"))
				.andExpect(jsonPath("$.data.campaignName").value("새 캠페인"));

		then(itemRepository).should().updateCampaign(16L, 50L);
	}

	@Test
	void PATCH_기존_캠페인에_연결되면_campaign을_동봉하지_않는다() throws Exception {
		given(itemRepository.findByIdAndUser(17L, 7L))
				.willReturn(Optional.of(urlItem(17L, null, LocalDate.now(KstTimestamps.KST))));
		given(campaignRepository.findByNameAndUser(eq("기존 캠페인"), eq(7L)))
				.willReturn(Optional.of(new CampaignRow(60L, 7L, "기존 캠페인", null, null, null, null, null,
						OffsetDateTime.parse("2026-07-01T00:00:00Z"))));

		mockMvc.perform(patch("/v1/monitoring/items/17").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"campaignName\":\"기존 캠페인\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", Matchers.not(Matchers.hasKey("campaign"))))
				.andExpect(jsonPath("$.data.campaignId").value("60"));
	}

	@Test
	void PATCH_campaignName_null이면_캠페인_연결이_해제된다() throws Exception {
		given(itemRepository.findByIdAndUser(18L, 7L))
				.willReturn(Optional.of(urlItem(18L, null, LocalDate.now(KstTimestamps.KST))));

		mockMvc.perform(patch("/v1/monitoring/items/18").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"campaignName\":null}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.campaignId").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.data.campaignName").value(Matchers.nullValue()));

		then(itemRepository).should().updateCampaign(18L, null);
	}

	@Test
	void PATCH_target_확정_행은_상태와_소문자_핸들을_유도해_응답한다() throws Exception {
		LocalDate registeredOn = LocalDate.now(KstTimestamps.KST);
		given(itemRepository.findByIdAndUser(19L, 7L)).willReturn(Optional.of(accountItem(19L, 900L, registeredOn)));
		given(readRepository.findTargets(List.of(900L)))
				.willReturn(List.of(targetRow("TRACKING", "GlowDeep", "SHORT1")));
		given(commandClient.extend(eq(900L), any())).willReturn(new ExtendResult(900L, OffsetDateTime.now()));

		// target 확정 + tracked_short_code가 있으면 post는 full 조립된다(PATCH/cancel 응답도 6.26
		// 어셈블러로 승격 — post_meta 미조회 상태에서도 caption=""·contentType=feed 기본값으로 채워진다).
		mockMvc.perform(patch("/v1/monitoring/items/19").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"trackingDays\":30}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("tracking"))
				.andExpect(jsonPath("$.data.handle").value("glowdeep"))
				.andExpect(jsonPath("$.data.post").value(Matchers.notNullValue()))
				.andExpect(jsonPath("$.data.post.url").value("https://www.instagram.com/p/SHORT1/"))
				.andExpect(jsonPath("$.data.post.snapshots").isArray())
				.andExpect(jsonPath("$.data.post.recentComments").isArray());
	}

	@Test
	void PATCH_target_확정_행의_연장_실패면_503이고_Retry_After가_있다() throws Exception {
		LocalDate registeredOn = LocalDate.now(KstTimestamps.KST);
		given(itemRepository.findByIdAndUser(20L, 7L)).willReturn(Optional.of(accountItem(20L, 900L, registeredOn)));
		given(readRepository.findTargets(List.of(900L)))
				.willReturn(List.of(targetRow("TRACKING", "GlowDeep", "SHORT1")));
		given(commandClient.extend(eq(900L), any())).willThrow(new MonitoringUnavailableException("연결 실패", null));

		mockMvc.perform(patch("/v1/monitoring/items/20").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"trackingDays\":30}"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
				.andExpect(header().string("Retry-After", "5"));

		then(itemRepository).should(never()).updateTrackingDays(anyLong(), anyInt());
	}

	// ── POST /v1/monitoring/items/{itemId}/cancel (6.30) ───────────────────────

	@Test
	void cancel_존재하지_않는_itemId는_404() throws Exception {
		given(itemRepository.findByIdAndUser(999L, 7L)).willReturn(Optional.empty());

		mockMvc.perform(post("/v1/monitoring/items/999/cancel").with(user(principal())).with(csrf()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void cancel_collecting_상태의_pending_url_행은_취소_불가_400() throws Exception {
		given(itemRepository.findByIdAndUser(21L, 7L))
				.willReturn(Optional.of(urlItem(21L, null, LocalDate.now(KstTimestamps.KST))));

		mockMvc.perform(post("/v1/monitoring/items/21/cancel").with(user(principal())).with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(commandClient).should(never()).cancel(anyLong());
		then(itemRepository).should(never()).markCanceled(anyLong(), anyString(), any());
	}

	@Test
	void cancel_만료된_pending_url_행은_ended로_유도돼_취소_불가_400() throws Exception {
		// registeredOn(30일 전) + trackingDays(14) = 이미 만료 → url 모드 pending은 ended로 유도(진행 중 아님).
		LocalDate registeredOn = LocalDate.now(KstTimestamps.KST).minusDays(30);
		given(itemRepository.findByIdAndUser(27L, 7L)).willReturn(Optional.of(urlItem(27L, null, registeredOn)));

		mockMvc.perform(post("/v1/monitoring/items/27/cancel").with(user(principal())).with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(commandClient).should(never()).cancel(anyLong());
		then(itemRepository).should(never()).markCanceled(anyLong(), anyString(), any());
	}

	@Test
	void cancel_이미_종결된_행은_취소_불가_400() throws Exception {
		given(itemRepository.findByIdAndUser(22L, 7L))
				.willReturn(Optional.of(canceledItem(22L, "tracking", LocalDate.now(KstTimestamps.KST))));

		mockMvc.perform(post("/v1/monitoring/items/22/cancel").with(user(principal())).with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void cancel_pending_detecting_행은_monitoring_호출_없이_not_uploaded로_전이한다() throws Exception {
		given(itemRepository.findByIdAndUser(23L, 7L))
				.willReturn(Optional.of(accountItem(23L, null, LocalDate.now(KstTimestamps.KST))));

		mockMvc.perform(post("/v1/monitoring/items/23/cancel").with(user(principal())).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("not_uploaded"))
				.andExpect(jsonPath("$.data.handle").value("glowdeep"));

		then(commandClient).should(never()).cancel(anyLong());
		then(itemRepository).should().markCanceled(eq(23L), eq("detecting"), any());
	}

	@Test
	void cancel_target_확정_tracking_행은_monitoring_cancel_후_ended로_전이한다() throws Exception {
		given(itemRepository.findByIdAndUser(24L, 7L)).willReturn(Optional.of(accountItem(24L, 900L,
				LocalDate.now(KstTimestamps.KST))));
		given(readRepository.findTargets(List.of(900L)))
				.willReturn(List.of(targetRow("TRACKING", "GlowDeep", "SHORT1")));
		given(commandClient.cancel(900L)).willReturn(new CancelResult(900L, "CANCELED"));

		mockMvc.perform(post("/v1/monitoring/items/24/cancel").with(user(principal())).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("ended"))
				.andExpect(jsonPath("$.data.handle").value("glowdeep"));

		then(commandClient).should().cancel(900L);
		then(itemRepository).should().markCanceled(eq(24L), eq("tracking"), any());
	}

	@Test
	void cancel_monitoring_호출_실패면_503이고_Retry_After가_있다() throws Exception {
		given(itemRepository.findByIdAndUser(25L, 7L)).willReturn(Optional.of(accountItem(25L, 900L,
				LocalDate.now(KstTimestamps.KST))));
		given(readRepository.findTargets(List.of(900L)))
				.willReturn(List.of(targetRow("TRACKING", "GlowDeep", "SHORT1")));
		given(commandClient.cancel(900L)).willThrow(new MonitoringUnavailableException("연결 실패", null));

		mockMvc.perform(post("/v1/monitoring/items/25/cancel").with(user(principal())).with(csrf()))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
				.andExpect(header().string("Retry-After", "5"));

		then(itemRepository).should(never()).markCanceled(anyLong(), anyString(), any());
	}
}
