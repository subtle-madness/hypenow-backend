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
import com.celfit.monitoring.store.BrandSeededAccountRepository;
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
		Integer receivedMonths;
		Long triggeredSweepBrandId;
		List<String> triggeredSweepTags;

		StubService() {
			super(null, null, null, null, null, null, Runnable::run, Runnable::run);
		}

		@Override
		public Result register(String username, String brandName, Integer collectionMonths) {
			receivedBrandName = brandName;
			receivedMonths = collectionMonths;
			if (toThrow != null) {
				throw toThrow;
			}
			return result;
		}

		@Override
		public DeregisterOutcome deregister(String username) {
			return outcome;
		}

		@Override
		public void triggerHashtagSweepIfNonEmpty(BrandRow row, List<String> tags) {
			triggeredSweepBrandId = row.id();
			triggeredSweepTags = tags;
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

	/** 태그 저장 스텁 — 조회 목록 주입 + 교체·추가·삭제 호출 인자 캡처. */
	private static final class StubHashtagRepository extends BrandHashtagRepository {
		List<String> tags = List.of();
		Long receivedTagsBrandId;
		List<String> receivedTags;
		Long addedTagsBrandId;
		List<String> addedTags;
		Long deletedTagBrandId;
		String deletedTag;
		Long deletedAllTagsBrandId;

		StubHashtagRepository() {
			super(null);
		}

		@Override
		public List<String> findTags(long brandId) {
			return tags;
		}

		@Override
		public void replaceTags(long brandId, List<String> tags) {
			receivedTagsBrandId = brandId;
			receivedTags = tags;
		}

		@Override
		public void addTags(long brandId, java.util.Collection<String> tags) {
			addedTagsBrandId = brandId;
			addedTags = List.copyOf(tags);
		}

		@Override
		public void deleteTag(long brandId, String tag) {
			deletedTagBrandId = brandId;
			deletedTag = tag;
		}

		@Override
		public void deleteAllTags(long brandId) {
			deletedAllTagsBrandId = brandId;
		}
	}

	/** 시딩 계정 저장 스텁 — 조회 목록 주입 + 교체·추가·삭제 호출 인자 캡처. */
	private static final class StubSeededAccountRepository extends BrandSeededAccountRepository {
		List<String> usernames = List.of();
		Long replacedBrandId;
		List<String> replaced;
		Long addedBrandId;
		List<String> added;
		Long deletedBrandId;
		String deleted;
		Long deletedAllBrandId;

		StubSeededAccountRepository() {
			super(null);
		}

		@Override
		public List<String> findUsernames(long brandId) {
			return usernames;
		}

		@Override
		public void replace(long brandId, List<String> usernames) {
			replacedBrandId = brandId;
			replaced = usernames;
		}

		@Override
		public void add(long brandId, java.util.Collection<String> usernames) {
			addedBrandId = brandId;
			added = List.copyOf(usernames);
		}

		@Override
		public void delete(long brandId, String username) {
			deletedBrandId = brandId;
			deleted = username;
		}

		@Override
		public void deleteAll(long brandId) {
			deletedAllBrandId = brandId;
		}
	}

	private final StubService service = new StubService();
	private final StubBrandRepository brands = new StubBrandRepository();
	private final StubHashtagRepository hashtags = new StubHashtagRepository();
	private final StubSeededAccountRepository seededAccounts = new StubSeededAccountRepository();
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new BrandController(service, brands, hashtags, seededAccounts))
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
	void 등록_요청의_collectionMonths를_서비스에_전달한다() throws Exception {
		service.result = new BrandRegistrationService.Result(42L, "brandx", 100L, false);

		mvc.perform(post("/api/brands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"brandx\", \"collectionMonths\": 3}"))
				.andExpect(status().isCreated());

		assertThat(service.receivedMonths).isEqualTo(3);
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

	// ---------- 태그 셋 관리(유저 입력, 2026-08-12) ----------

	@Test
	void 태그_조회는_현재_활성_태그_목록을_돌려준다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);
		hashtags.tags = List.of("cclime", "끌리메");

		mvc.perform(get("/api/brands/brandx/hashtag-tags"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tags[0]").value("cclime"))
				.andExpect(jsonPath("$.tags[1]").value("끌리메"));
	}

	@Test
	void 태그_교체는_정규화_후_전체_교체한다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		// # 제거 · 대소문자 통일 · 중복 제거(입력 순서 보존)
		mvc.perform(put("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\":[\"#CClime\",\" cclime \",\"NewTag\"]}"))
				.andExpect(status().isNoContent());

		assertThat(hashtags.receivedTagsBrandId).isEqualTo(1L);
		assertThat(hashtags.receivedTags).containsExactly("cclime", "newtag");
	}

	@Test
	void 태그_재추가와_삭제_시나리오는_정규화된_전체_목록을_그대로_전달한다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);
		hashtags.tags = List.of("cclime", "끌리메");

		// "끌리메" 삭제하고 "새태그" 추가한 최종 목록을 PUT — 리포지토리가 tombstone 판정을 수행
		mvc.perform(put("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\":[\"cclime\",\"새태그\"]}"))
				.andExpect(status().isNoContent());
		assertThat(hashtags.receivedTags).containsExactly("cclime", "새태그");

		// 이후 "끌리메"를 다시 추가(재활성) — 컨트롤러는 그대로 전달만, 재활성 자체는 리포지토리 책임
		mvc.perform(put("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\":[\"cclime\",\"새태그\",\"끌리메\"]}"))
				.andExpect(status().isNoContent());
		assertThat(hashtags.receivedTags).containsExactly("cclime", "새태그", "끌리메");
	}

	/**
	 * 2026-08-12 이후 PUT 빈 목록은 허용된다(단건·전체 삭제 API가 생겨 "전부 지우기"가 정당한
	 * 상태 — 구 하한 가드는 폐지됐다). 빈 목록 PUT은 브랜드 태그 감지 전체를 끄는 것과 같다.
	 */
	@Test
	void 태그_빈_목록_교체는_허용된다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		mvc.perform(put("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\":[\"  \",\"\"]}"))
				.andExpect(status().isNoContent());

		assertThat(hashtags.receivedTags).isEmpty();
	}

	@Test
	void 태그_null_바디는_빈_목록_교체로_허용된다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		mvc.perform(put("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isNoContent());

		assertThat(hashtags.receivedTags).isEmpty();
	}

	// ---------- 태그 등록 시 즉시 스윕 트리거(2026-08-17) ----------

	@Test
	void 태그_교체가_비어있지_않으면_즉시_스윕을_트리거한다() throws Exception {
		brands.row = new BrandRow(7L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		mvc.perform(put("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\":[\"cclime\"]}"))
				.andExpect(status().isNoContent());

		assertThat(service.triggeredSweepBrandId).isEqualTo(7L);
		assertThat(service.triggeredSweepTags).containsExactly("cclime");
	}

	@Test
	void 태그_교체_결과가_빈_목록이어도_트리거_메서드는_호출된다_빈_목록_판단은_서비스_책임() throws Exception {
		// 컨트롤러는 정규화된 태그 그대로 서비스에 넘기고, "비어있으면 스킵"은 서비스 쪽 책임이다
		// (BrandRegistrationServiceTest에서 검증) — 여기서는 컨트롤러가 정확한 인자를 넘기는지만 본다.
		brands.row = new BrandRow(7L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		mvc.perform(put("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isNoContent());

		assertThat(service.triggeredSweepBrandId).isEqualTo(7L);
		assertThat(service.triggeredSweepTags).isEmpty();
	}

	// ---------- 태그 단건 추가·삭제(2026-08-12, 표준 REST 확장) ----------

	@Test
	void 태그_추가는_정규화_검증_후_전달한다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		mvc.perform(post("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\":[\"#CClime\",\" cclime \"]}"))
				.andExpect(status().isNoContent());

		assertThat(hashtags.addedTagsBrandId).isEqualTo(1L);
		assertThat(hashtags.addedTags).containsExactly("cclime");
		assertThat(service.triggeredSweepBrandId).isEqualTo(1L);
		assertThat(service.triggeredSweepTags).containsExactly("cclime");
	}

	@Test
	void 태그_단건_삭제는_스윕을_트리거하지_않는다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		mvc.perform(delete("/api/brands/brandx/hashtag-tags/{tag}", "cclime"))
				.andExpect(status().isNoContent());

		assertThat(service.triggeredSweepBrandId).isNull();
	}

	@Test
	void 태그_전체_삭제는_스윕을_트리거하지_않는다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		mvc.perform(delete("/api/brands/brandx/hashtag-tags"))
				.andExpect(status().isNoContent());

		assertThat(service.triggeredSweepBrandId).isNull();
	}

	/** POST는 PUT과 달리 빈 입력을 422로 거부한다 — "추가할 게 없다"는 요청 자체가 실수일 확률이 높다. */
	@Test
	void 태그_추가는_빈_입력이면_422다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		mvc.perform(post("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("VALIDATION"));

		assertThat(hashtags.addedTags).isNull();
	}

	@Test
	void 태그_추가는_무효_문자면_422다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		mvc.perform(post("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\":[\"tag.dot\"]}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("VALIDATION"));

		assertThat(hashtags.addedTags).isNull();
	}

	@Test
	void 태그_단건_삭제는_정규화_후_전달하고_204다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		// URI 템플릿 변수로 넘겨 인코딩을 MockMvc/UriComponentsBuilder에 맡긴다 — 리터럴 "%23"을
		// 그대로 문자열에 박으면 이중 인코딩되어 서버가 percent-literal을 그대로 받는다(왕복 실패).
		mvc.perform(delete("/api/brands/brandx/hashtag-tags/{tag}", "#CClime"))
				.andExpect(status().isNoContent());

		assertThat(hashtags.deletedTagBrandId).isEqualTo(1L);
		assertThat(hashtags.deletedTag).isEqualTo("cclime");
	}

	/** 한글 태그 경로 변수 — URL 인코딩 왕복이 정규화 이전에 이미 디코딩돼 들어와야 한다. */
	@Test
	void 태그_단건_삭제는_한글_태그도_정상_디코딩된다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		mvc.perform(delete("/api/brands/brandx/hashtag-tags/{tag}", "끌리메"))
				.andExpect(status().isNoContent());

		assertThat(hashtags.deletedTagBrandId).isEqualTo(1L);
		assertThat(hashtags.deletedTag).isEqualTo("끌리메");
	}

	@Test
	void 태그_전체_삭제는_204다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		mvc.perform(delete("/api/brands/brandx/hashtag-tags"))
				.andExpect(status().isNoContent());

		assertThat(hashtags.deletedAllTagsBrandId).isEqualTo(1L);
	}

	/** 유효 문자 위반은 절삭이 아니라 거부 — 공백(끌리 메)·점(tag.dot) 모두 VALID_TAG 전체 일치 실패. */
	@Test
	void 유효_문자_위반_태그는_절삭하지_않고_422로_거부한다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		mvc.perform(put("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\":[\"끌리 메\"]}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("VALIDATION"))
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("끌리 메")));
		assertThat(hashtags.receivedTags).isNull();

		mvc.perform(put("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\":[\"tag.dot\"]}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("VALIDATION"))
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("tag.dot")));
		assertThat(hashtags.receivedTags).isNull();
	}

	@Test
	void 태그_미등록_브랜드는_404이고_에러_바디를_준다() throws Exception {
		brands.row = null;

		mvc.perform(get("/api/brands/ghost/hashtag-tags"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BRAND_NOT_FOUND"));
		mvc.perform(put("/api/brands/ghost/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\":[\"a\"]}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BRAND_NOT_FOUND"));
	}

	@Test
	void 태그_탈퇴한_브랜드도_404이고_에러_바디를_준다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.CLOSED, null, 12);

		mvc.perform(get("/api/brands/brandx/hashtag-tags"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BRAND_NOT_FOUND"));
		mvc.perform(put("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\":[\"a\"]}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BRAND_NOT_FOUND"));
	}

	// ---------- 시딩 계정(스펙 §6, 2026-08-18) ----------

	@Test
	void 시딩_계정_조회는_현재_등록된_계정_목록을_돌려준다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);
		seededAccounts.usernames = List.of("influencer1");

		mvc.perform(get("/api/brands/brandx/seeded-accounts"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.usernames[0]").value("influencer1"));
	}

	@Test
	void 시딩_계정_전체_교체는_정규화_후_전달한다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		// trim · 대소문자 통일 · 중복 제거(입력 순서 보존) — 태그와 달리 선행 # 제거는 없다
		mvc.perform(put("/api/brands/brandx/seeded-accounts").contentType(MediaType.APPLICATION_JSON)
						.content("{\"usernames\":[\"influencer1\",\" Influencer2 \",\"influencer1\"]}"))
				.andExpect(status().isNoContent());

		assertThat(seededAccounts.replacedBrandId).isEqualTo(1L);
		assertThat(seededAccounts.replaced).containsExactly("influencer1", "influencer2");
	}

	/** "@handle" 형태 입력이 시딩 조인을 깨는 공백이 있어 2026-08-18 추가(스펙 §6). */
	@Test
	void 시딩_계정_전체_교체는_선행_골뱅이를_제거하고_저장한다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		mvc.perform(put("/api/brands/brandx/seeded-accounts").contentType(MediaType.APPLICATION_JSON)
						.content("{\"usernames\":[\"@Influencer1\"]}"))
				.andExpect(status().isNoContent());

		assertThat(seededAccounts.replaced).containsExactly("influencer1");

		seededAccounts.usernames = seededAccounts.replaced;
		mvc.perform(get("/api/brands/brandx/seeded-accounts"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.usernames[0]").value("influencer1"));
	}

	@Test
	void 시딩_계정_추가는_정규화_후_전달한다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		mvc.perform(post("/api/brands/brandx/seeded-accounts").contentType(MediaType.APPLICATION_JSON)
						.content("{\"usernames\":[\" Influencer3 \"]}"))
				.andExpect(status().isNoContent());

		assertThat(seededAccounts.addedBrandId).isEqualTo(1L);
		assertThat(seededAccounts.added).containsExactly("influencer3");
	}

	/** 태그 POST와 달리 빈 입력을 422로 거부하지 않는다 — repository.add()가 빈 컬렉션에 no-op이라
	 * 컨트롤러가 별도로 막을 이유가 없다(계획 §Task 12 Step 3 원안 그대로). */
	@Test
	void 시딩_계정_추가는_빈_입력이면_빈_컬렉션으로_전달되고_204다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		mvc.perform(post("/api/brands/brandx/seeded-accounts").contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isNoContent());

		assertThat(seededAccounts.addedBrandId).isEqualTo(1L);
		assertThat(seededAccounts.added).isEmpty();
	}

	@Test
	void 시딩_계정_단건_삭제는_정규화_후_전달하고_204다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		mvc.perform(delete("/api/brands/brandx/seeded-accounts/{username}", "Influencer1"))
				.andExpect(status().isNoContent());

		assertThat(seededAccounts.deletedBrandId).isEqualTo(1L);
		assertThat(seededAccounts.deleted).isEqualTo("influencer1");
	}

	@Test
	void 시딩_계정_전체_삭제는_204다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12);

		mvc.perform(delete("/api/brands/brandx/seeded-accounts"))
				.andExpect(status().isNoContent());

		assertThat(seededAccounts.deletedAllBrandId).isEqualTo(1L);
	}

	@Test
	void 시딩_계정_미등록_브랜드는_404이고_에러_바디를_준다() throws Exception {
		brands.row = null;

		mvc.perform(get("/api/brands/ghost/seeded-accounts"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BRAND_NOT_FOUND"));
		mvc.perform(put("/api/brands/ghost/seeded-accounts").contentType(MediaType.APPLICATION_JSON)
						.content("{\"usernames\":[\"a\"]}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BRAND_NOT_FOUND"));
	}

	@Test
	void 시딩_계정_탈퇴한_브랜드도_404이고_에러_바디를_준다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.CLOSED, null, 12);

		mvc.perform(get("/api/brands/brandx/seeded-accounts"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BRAND_NOT_FOUND"));
	}
}
