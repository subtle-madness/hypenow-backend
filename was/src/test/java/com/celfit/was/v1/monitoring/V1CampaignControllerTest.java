package com.celfit.was.v1.monitoring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** /v1/monitoring/campaigns 계약(스펙 6.25 Campaign, 6.31) — CRUD·null 의미론·KST 오프셋 직렬화. */
@WebMvcTest(controllers = V1CampaignController.class, properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({V1CampaignService.class, V1ExceptionAdvice.class, SecurityConfig.class})
class V1CampaignControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	CampaignRepository repository;

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	private static CampaignRow row(long id, String name) {
		return new CampaignRow(id, 7L, name, "설명", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31), "브랜드A",
				1_000_000L, 40, OffsetDateTime.parse("2026-07-29T00:00:00Z"));
	}

	@Test
	void 목록은_전량_반환하고_meta_total은_data_길이와_같다() throws Exception {
		given(repository.findByUser(7L)).willReturn(List.of(row(1, "캠페인1"), row(2, "캠페인2")));

		mockMvc.perform(get("/v1/monitoring/campaigns").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[0].id").value("1"))
				.andExpect(jsonPath("$.data[1].id").value("2"))
				.andExpect(jsonPath("$.meta.total").value(2));
	}

	@Test
	void createdAt은_KST_오프셋_09_00_문자열이다() throws Exception {
		given(repository.findByUser(7L)).willReturn(List.of(row(1, "캠페인1")));

		// UTC 2026-07-29T00:00:00Z → KST(+9h) 2026-07-29T09:00:00+09:00
		mockMvc.perform(get("/v1/monitoring/campaigns").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].createdAt").value("2026-07-29T09:00:00+09:00"));
	}

	@Test
	void description이_null이면_키_자체는_JSON에_명시적으로_존재한다() throws Exception {
		CampaignRow noDescription = new CampaignRow(1, 7L, "캠페인1", null, null, null, null, null, null,
				OffsetDateTime.parse("2026-07-29T00:00:00Z"));
		given(repository.findByUser(7L)).willReturn(List.of(noDescription));

		mockMvc.perform(get("/v1/monitoring/campaigns").with(user(principal())))
				.andExpect(status().isOk())
				// 키 존재 확인(생략 아님) + 값이 null인지 둘 다 단언 — 계약 무결성 규칙 #1(1.8).
				.andExpect(jsonPath("$.data[0]", Matchers.hasKey("description")))
				.andExpect(jsonPath("$.data[0].description").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.data[0]", Matchers.hasKey("seedingCount")))
				.andExpect(jsonPath("$.data[0].seedingCount").value(Matchers.nullValue()));
	}

	@Test
	void 생성은_201이고_Campaign을_돌려준다() throws Exception {
		given(repository.insert(eq(7L), eq("여름 캠페인"), eq("설명"), eq(LocalDate.of(2026, 7, 1)),
				eq(LocalDate.of(2026, 8, 31)), eq("브랜드A"), eq(1_000_000L), eq(40)))
				.willReturn(row(10, "여름 캠페인"));

		mockMvc.perform(post("/v1/monitoring/campaigns").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"여름 캠페인","description":"설명","startDate":"2026-07-01",
								 "endDate":"2026-08-31","brand":"브랜드A","budget":1000000,"seedingCount":40}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.id").value("10"))
				.andExpect(jsonPath("$.data.name").value("여름 캠페인"))
				.andExpect(jsonPath("$.data.seedingCount").value(40));
	}

	@Test
	void 생성_seedingCount_생략하면_응답에_null_키로_포함된다() throws Exception {
		given(repository.insert(eq(7L), eq("시딩생략캠페인"), isNull(), isNull(), isNull(), isNull(), isNull(),
				isNull())).willReturn(row(11, "시딩생략캠페인"));

		mockMvc.perform(post("/v1/monitoring/campaigns").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"시딩생략캠페인"}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data", Matchers.hasKey("seedingCount")));
	}

	@Test
	void 이름_누락은_400_VALIDATION_FAILED다() throws Exception {
		mockMvc.perform(post("/v1/monitoring/campaigns").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"description":"이름 없음"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(repository).should(never()).insert(anyLong(), anyString(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void budget이_소수면_400_VALIDATION_FAILED다() throws Exception {
		mockMvc.perform(post("/v1/monitoring/campaigns").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"캠페인","budget":12.5}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(repository).should(never()).insert(anyLong(), anyString(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void seedingCount가_음수면_400_VALIDATION_FAILED_한국어_메시지다() throws Exception {
		mockMvc.perform(post("/v1/monitoring/campaigns").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"캠페인","seedingCount":-1}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.error.message").value("시딩 인원은 0 이상의 정수로 입력해 주세요."));

		then(repository).should(never()).insert(anyLong(), anyString(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void seedingCount가_소수면_400_VALIDATION_FAILED다() throws Exception {
		mockMvc.perform(post("/v1/monitoring/campaigns").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"캠페인","seedingCount":12.5}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(repository).should(never()).insert(anyLong(), anyString(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void 이름에_금지_문자가_있으면_400_VALIDATION_FAILED다() throws Exception {
		mockMvc.perform(post("/v1/monitoring/campaigns").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"여름/캠페인"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(repository).should(never()).insert(anyLong(), anyString(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void 이름_중복은_409_CAMPAIGN_NAME_EXISTS다() throws Exception {
		given(repository.insert(eq(7L), eq("중복캠페인"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
				.willThrow(new DuplicateKeyException("dup"));

		mockMvc.perform(post("/v1/monitoring/campaigns").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"중복캠페인"}"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("CAMPAIGN_NAME_EXISTS"))
				.andExpect(jsonPath("$.error.message").value("같은 이름의 캠페인이 이미 있어요."));
	}

	@Test
	void PATCH_키가_없으면_기존값을_유지한다() throws Exception {
		given(repository.findByIdAndUser(10L, 7L)).willReturn(Optional.of(row(10, "원래이름")));
		given(repository.update(eq(10L), eq("원래이름"), eq("설명"), eq(LocalDate.of(2026, 7, 1)),
				eq(LocalDate.of(2026, 8, 31)), eq("브랜드A"), eq(1_000_000L), eq(40)))
				.willReturn(row(10, "원래이름"));

		// budget·seedingCount 키를 아예 안 보냄 → 기존 값(1,000,000 / 40) 유지
		mockMvc.perform(patch("/v1/monitoring/campaigns/10").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{}"""))
				.andExpect(status().isOk());

		then(repository).should().update(10L, "원래이름", "설명", LocalDate.of(2026, 7, 1),
				LocalDate.of(2026, 8, 31), "브랜드A", 1_000_000L, 40);
	}

	@Test
	void PATCH_키가_있고_null이면_값을_해제한다() throws Exception {
		given(repository.findByIdAndUser(10L, 7L)).willReturn(Optional.of(row(10, "원래이름")));
		given(repository.update(eq(10L), eq("원래이름"), isNull(), eq(LocalDate.of(2026, 7, 1)),
				eq(LocalDate.of(2026, 8, 31)), eq("브랜드A"), isNull(), isNull()))
				.willReturn(row(10, "원래이름"));

		// description·budget·seedingCount를 명시적 null로 보냄 → 해제
		mockMvc.perform(patch("/v1/monitoring/campaigns/10").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"description":null,"budget":null,"seedingCount":null}"""))
				.andExpect(status().isOk());

		then(repository).should().update(10L, "원래이름", null, LocalDate.of(2026, 7, 1),
				LocalDate.of(2026, 8, 31), "브랜드A", null, null);
	}

	@Test
	void PATCH_seedingCount가_음수면_400이고_update는_호출되지_않는다() throws Exception {
		given(repository.findByIdAndUser(10L, 7L)).willReturn(Optional.of(row(10, "원래이름")));

		mockMvc.perform(patch("/v1/monitoring/campaigns/10").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"seedingCount":-1}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.error.message").value("시딩 인원은 0 이상의 정수로 입력해 주세요."));

		then(repository).should(never()).update(anyLong(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void PATCH_이름을_null로_보내면_400이다() throws Exception {
		given(repository.findByIdAndUser(10L, 7L)).willReturn(Optional.of(row(10, "원래이름")));

		mockMvc.perform(patch("/v1/monitoring/campaigns/10").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":null}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(repository).should(never()).update(anyLong(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void PATCH_없는_캠페인은_404다() throws Exception {
		given(repository.findByIdAndUser(999L, 7L)).willReturn(Optional.empty());

		mockMvc.perform(patch("/v1/monitoring/campaigns/999").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"새이름"}"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void PATCH_숫자가_아닌_campaignId는_404다() throws Exception {
		mockMvc.perform(patch("/v1/monitoring/campaigns/abc").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"새이름"}"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

		then(repository).should(never()).findByIdAndUser(anyLong(), anyLong());
	}

	@Test
	void 삭제는_204다() throws Exception {
		given(repository.findByIdAndUser(10L, 7L)).willReturn(Optional.of(row(10, "삭제될캠페인")));

		mockMvc.perform(delete("/v1/monitoring/campaigns/10").with(user(principal())).with(csrf()))
				.andExpect(status().isNoContent());

		then(repository).should().delete(10L);
	}

	@Test
	void 없는_캠페인_삭제는_404다() throws Exception {
		given(repository.findByIdAndUser(999L, 7L)).willReturn(Optional.empty());

		mockMvc.perform(delete("/v1/monitoring/campaigns/999").with(user(principal())).with(csrf()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

		then(repository).should(never()).delete(anyLong());
	}
}
