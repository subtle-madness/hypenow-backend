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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.OffsetDateTime;
import java.util.Optional;
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
 * /v1/monitoring/items POST(스펙 6.27) — 구조 검증 400 전종·201 정상(혼합 등록)·campaign 동봉
 * 조건·전체 실패 시 빈 items·nullable 키 명시적 존재를 컨트롤러 슬라이스로 검증한다. V1CampaignService는
 * 실 빈으로 붙여 resolveOrCreate 경로까지 통과시키고, DB 접근은 리포지토리 3종만 mock한다
 * (V1CampaignControllerTest 관용구와 동일).
 */
@WebMvcTest(controllers = V1MonitoringItemsController.class, properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({V1MonitoringRegistrationService.class, V1CampaignService.class, V1ExceptionAdvice.class, SecurityConfig.class})
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

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	@BeforeEach
	void 등록_요청_기본_스텁() {
		given(registrationRepository.insert(anyLong(), anyInt(), any())).willReturn(555L);
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
}
