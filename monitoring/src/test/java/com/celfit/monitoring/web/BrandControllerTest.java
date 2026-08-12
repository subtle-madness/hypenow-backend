package com.celfit.monitoring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.hiker.SubjectNotFoundException;
import com.celfit.monitoring.service.BrandRegistrationService;
import com.celfit.monitoring.service.ValidationException;
import com.celfit.monitoring.store.BrandHashtagRepository;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import java.util.List;
import java.util.Optional;
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
		String receivedBrandName;

		StubService() {
			super(null, null, null, null, null, null, Runnable::run, Runnable::run);
		}

		@Override
		public Result register(String username, String brandName) {
			receivedBrandName = brandName;
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

	/** 브랜드 해석 스텁 — row가 null이면 미등록, status로 ACTIVE/CLOSED를 가른다. */
	private static final class StubBrandRepository extends BrandRepository {
		BrandRow row;

		StubBrandRepository() {
			super(null);
		}

		@Override
		public Optional<BrandRow> findByUsername(String username) {
			return Optional.ofNullable(row);
		}
	}

	/** 제외 문자열 저장 스텁 — 조회 목록 주입 + 교체 호출 인자 캡처. */
	private static final class StubHashtagRepository extends BrandHashtagRepository {
		List<String> terms = List.of();
		Long receivedBrandId;
		List<String> receivedTerms;

		StubHashtagRepository() {
			super(null);
		}

		@Override
		public List<String> findExclusionTerms(long brandId) {
			return terms;
		}

		@Override
		public void replaceExclusionTerms(long brandId, List<String> terms) {
			receivedBrandId = brandId;
			receivedTerms = terms;
		}
	}

	private final StubService service = new StubService();
	private final StubBrandRepository brands = new StubBrandRepository();
	private final StubHashtagRepository hashtags = new StubHashtagRepository();
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new BrandController(service, brands, hashtags))
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
	void brandName을_함께_전달한다() throws Exception {
		service.result = new BrandRegistrationService.Result(1L, "brandx", 1234L, false);

		mvc.perform(post("/api/brands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"brandx\",\"brandName\":\"끌리메\"}"))
				.andExpect(status().isCreated());

		assertThat(service.receivedBrandName).isEqualTo("끌리메");
	}

	@Test
	void brandName_없는_기존_요청도_하위_호환으로_동작한다() throws Exception {
		service.result = new BrandRegistrationService.Result(1L, "brandx", 1234L, false);

		mvc.perform(post("/api/brands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"brandx\"}"))
				.andExpect(status().isCreated());

		assertThat(service.receivedBrandName).isNull();
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

	@Test
	void 제외_문자열_조회는_현재_목록을_돌려준다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null);
		hashtags.terms = List.of("cclime", "끌리메");

		mvc.perform(get("/api/brands/brandx/hashtag-exclusions"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.terms[0]").value("cclime"))
				.andExpect(jsonPath("$.terms[1]").value("끌리메"));
	}

	@Test
	void 제외_문자열_교체는_정규화_후_전체_교체한다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null);

		mvc.perform(put("/api/brands/brandx/hashtag-exclusions").contentType(MediaType.APPLICATION_JSON)
						.content("{\"terms\":[\" CClime \",\"cclime\",\"\"]}"))
				.andExpect(status().isNoContent());

		assertThat(hashtags.receivedBrandId).isEqualTo(1L);
		assertThat(hashtags.receivedTerms).containsExactly("cclime");
	}

	/**
	 * 정규화 결과가 빈 목록이면 422로 거부한다(비소급 오염 방지 — 판정은 이미 저장된 verdict
	 * 불변이라, 빈 목록으로 전부 지우면 자사 게시물이 RELEVANT로 저장된 뒤 되돌려도 복구 불가).
	 */
	@Test
	void terms_null_바디는_빈_목록_교체라_422다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null);

		mvc.perform(put("/api/brands/brandx/hashtag-exclusions").contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("VALIDATION"));

		assertThat(hashtags.receivedTerms).isNull();
	}

	@Test
	void 빈_배열_교체도_422다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null);

		mvc.perform(put("/api/brands/brandx/hashtag-exclusions").contentType(MediaType.APPLICATION_JSON)
						.content("{\"terms\":[\"  \",\"\"]}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("VALIDATION"));

		assertThat(hashtags.receivedTerms).isNull();
	}

	@Test
	void 미등록_브랜드는_404이고_에러_바디를_준다() throws Exception {
		// was MonitoringCommandClient.exchange()가 code 있는 바디만 MonitoringApiException으로
		// 승격한다(빈 바디는 MonitoringUnavailableException/503으로 오승격 — 08-11 실측) — 그래서
		// deregister의 빈 바디 404와 달리 여기는 계약 §2 어휘를 채운 바디가 필수다.
		brands.row = null;

		mvc.perform(get("/api/brands/ghost/hashtag-exclusions"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BRAND_NOT_FOUND"));
		mvc.perform(put("/api/brands/ghost/hashtag-exclusions").contentType(MediaType.APPLICATION_JSON)
						.content("{\"terms\":[]}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BRAND_NOT_FOUND"));
	}

	@Test
	void 탈퇴한_브랜드도_404이고_에러_바디를_준다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.CLOSED, null);

		mvc.perform(get("/api/brands/brandx/hashtag-exclusions"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BRAND_NOT_FOUND"));
		mvc.perform(put("/api/brands/brandx/hashtag-exclusions").contentType(MediaType.APPLICATION_JSON)
						.content("{\"terms\":[]}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BRAND_NOT_FOUND"));
	}
}
