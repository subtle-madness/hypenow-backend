package com.celfit.monitoring.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.monitoring.hiker.SubjectNotFoundException;
import com.celfit.monitoring.service.BrandRegistrationService;
import com.celfit.monitoring.service.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 브랜드 등록/탈퇴 API — 상태 코드 계약(201 신규 / 200 replay / 204 탈퇴·멱등 / 404 미등록 /
 * 400 형식 위반 / 404 IG 계정 부재). 서비스는 스텁 — 상태 코드 매핑만 본다(standalone MockMvc,
 * 예외 매핑은 실제 ApiExceptionHandler를 태운다).
 */
class BrandControllerTest {

	/** 시나리오별 반환을 주입하는 스텁 — 협력자는 쓰지 않으므로 null 배선. */
	private static final class StubService extends BrandRegistrationService {
		Result result;
		DeregisterOutcome outcome;
		RuntimeException toThrow;

		StubService() {
			super(null, null, null, Runnable::run);
		}

		@Override
		public Result register(String username) {
			if (toThrow != null) {
				throw toThrow;
			}
			return result;
		}

		@Override
		public DeregisterOutcome deregister(String username) {
			return outcome;
		}
	}

	private final StubService service = new StubService();
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new BrandController(service))
				.setControllerAdvice(new ApiExceptionHandler())
				.build();
	}

	@Test
	void 신규_등록은_201이다() throws Exception {
		service.result = new BrandRegistrationService.Result(1L, "brandx", 1234L, false);

		mvc.perform(post("/api/brands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"brandx\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.brandId").value(1))
				.andExpect(jsonPath("$.username").value("brandx"))
				.andExpect(jsonPath("$.followers").value(1234))
				.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	@Test
	void 활성_재등록_replay는_200이다() throws Exception {
		service.result = new BrandRegistrationService.Result(1L, "brandx", null, true);

		mvc.perform(post("/api/brands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"brandx\"}"))
				.andExpect(status().isOk());
	}

	@Test
	void username_형식_위반은_400이다() throws Exception {
		service.toThrow = new ValidationException("username은 필수다");

		mvc.perform(post("/api/brands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION"));
	}

	@Test
	void IG_계정_부재는_404다() throws Exception {
		service.toThrow = new SubjectNotFoundException("계정 없음");

		mvc.perform(post("/api/brands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"ghost\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
	}

	@Test
	void 탈퇴는_204이고_이미_닫힘도_멱등_204다() throws Exception {
		service.outcome = BrandRegistrationService.DeregisterOutcome.CLOSED;
		mvc.perform(delete("/api/brands/brandx")).andExpect(status().isNoContent());

		service.outcome = BrandRegistrationService.DeregisterOutcome.ALREADY_CLOSED;
		mvc.perform(delete("/api/brands/brandx")).andExpect(status().isNoContent());
	}

	@Test
	void 미등록_탈퇴는_404다() throws Exception {
		service.outcome = BrandRegistrationService.DeregisterOutcome.NOT_FOUND;
		mvc.perform(delete("/api/brands/ghost")).andExpect(status().isNotFound());
	}
}
