package com.celfit.monitoring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.PostShapeUnsupportedException;
import com.celfit.monitoring.hiker.SubjectNotFoundException;
import com.celfit.monitoring.service.BrandDirectCollectService;
import com.celfit.monitoring.service.BrandRegistrationService;
import com.celfit.monitoring.service.ValidationException;
import com.celfit.monitoring.store.BrandHashtagRepository;
import com.celfit.monitoring.store.BrandLegacyHistoryCopier;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import com.celfit.monitoring.store.TaggedPostRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
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
		String receivedAccountType;
		Long triggeredSweepBrandId;
		List<String> triggeredSweepTags;

		StubService() {
			super(null, null, null, null, null, null, 2000,
					Runnable::run, Runnable::run, Runnable::run);
		}

		/**
		 * 4인자 오버라이드(2026-08-19 경쟁사 판정 제거 설계 §2) — BrandController가 실제로 호출하는
		 * 시그니처. 3인자 register는 이제 base 클래스의 위임 오버로드일 뿐이라 여기서 오버라이드하면
		 * 컨트롤러 호출이 도달하지 않는다(과거 실수 방지 주석).
		 */
		@Override
		public Result register(String username, String brandName, Integer collectionMonths, String accountType) {
			receivedBrandName = brandName;
			receivedMonths = collectionMonths;
			receivedAccountType = accountType;
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
		String setHasOwnLinkUsername;
		Boolean setHasOwnLinkValue;

		StubBrandRepository() {
			super(null);
		}

		@Override
		public Optional<BrandRow> findByUsername(String username) {
			return Optional.ofNullable(row);
		}

		@Override
		public Optional<BrandRow> findById(long brandId) {
			return row != null && row.id() == brandId ? Optional.of(row) : Optional.empty();
		}

		/** own-link PUT(2026-08-19 경쟁사 판정 제거 설계 §2) — 호출 인자만 캡처한다. */
		@Override
		public void setHasOwnLink(String username, boolean hasOwnLink) {
			setHasOwnLinkUsername = username;
			setHasOwnLinkValue = hasOwnLink;
		}
	}

	/** direct 등록 조회·취소 스텁 — findDirectSnapshot·deleteIfDirectOnly·clearDirect 호출 인자 캡처. */
	private static final class StubTaggedPosts extends TaggedPostRepository {
		Optional<DirectSnapshot> snapshot = Optional.empty();
		boolean deleteIfDirectOnlyResult;
		Long deleteIfDirectOnlyBrandId;
		String deleteIfDirectOnlyShortCode;
		Long clearDirectBrandId;
		String clearDirectShortCode;

		StubTaggedPosts() {
			super(null);
		}

		@Override
		public Optional<DirectSnapshot> findDirectSnapshot(long brandId, String shortCode) {
			return snapshot;
		}

		@Override
		public boolean deleteIfDirectOnly(long brandId, String shortCode) {
			deleteIfDirectOnlyBrandId = brandId;
			deleteIfDirectOnlyShortCode = shortCode;
			return deleteIfDirectOnlyResult;
		}

		@Override
		public void clearDirect(long brandId, String shortCode) {
			clearDirectBrandId = brandId;
			clearDirectShortCode = shortCode;
		}
	}

	/** direct 단건 수집 스텁 — 반환값·예외를 주입하고 전달 인자를 캡처한다. */
	private static final class StubDirectCollect extends BrandDirectCollectService {
		PostInfo result;
		RuntimeException toThrow;
		BrandRow receivedBrand;
		String receivedShortCode;
		Instant receivedRegisteredAt;

		StubDirectCollect() {
			super(null, null, null, null, null, 300, 2000);
		}

		@Override
		public PostInfo collectAndEnrich(BrandRow brand, String shortCode, Instant registeredAt) {
			receivedBrand = brand;
			receivedShortCode = shortCode;
			receivedRegisteredAt = registeredAt;
			if (toThrow != null) {
				throw toThrow;
			}
			return result;
		}
	}

	/** 레거시 이력 복사 스텁 — 호출 여부·인자만 캡처(실제 SQL은 BrandLegacyHistoryCopierTest가 검증). */
	private static final class StubLegacyHistoryCopier extends BrandLegacyHistoryCopier {
		String copiedShortCode;

		StubLegacyHistoryCopier() {
			super(null);
		}

		@Override
		public void copy(String shortCode) {
			copiedShortCode = shortCode;
		}
	}

	/** 태그 저장 스텁 — 조회 목록 주입 + 교체·추가·삭제 호출 인자 캡처. */
	private static final class StubHashtagRepository extends BrandHashtagRepository {
		List<String> tags = List.of();
		List<BrandHashtagRepository.RunStateRow> runStates = List.of();
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
		public List<BrandHashtagRepository.RunStateRow> findRunStates(long brandId) {
			return runStates;
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

	private final StubService service = new StubService();
	private final StubBrandRepository brands = new StubBrandRepository();
	private final StubHashtagRepository hashtags = new StubHashtagRepository();
	private final StubTaggedPosts taggedPosts = new StubTaggedPosts();
	private final StubDirectCollect directCollect = new StubDirectCollect();
	private final StubLegacyHistoryCopier legacyHistoryCopier = new StubLegacyHistoryCopier();
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new BrandController(service, brands, hashtags, taggedPosts,
						directCollect, legacyHistoryCopier))
				.setControllerAdvice(new ApiExceptionHandler())
				.build();
	}

	private static PostInfo samplePost(String shortCode, long takenAt) {
		return new PostInfo(shortCode, "author1", null, null, "101", "REELS", "캡션", null,
				takenAt, 10L, 2L, 500L, null, null, null, null, null, null, null, true, false, false);
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

	// ---------- accountType 전달·own-link(2026-08-19 경쟁사 판정 제거 설계 §2) ----------

	@Test
	void 등록_요청의_accountType을_서비스에_전달한다() throws Exception {
		service.result = new BrandRegistrationService.Result(42L, "brandx", 100L, false);

		mvc.perform(post("/api/brands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"brandx\", \"accountType\": \"competitor\"}"))
				.andExpect(status().isCreated());

		assertThat(service.receivedAccountType).isEqualTo("competitor");
	}

	@Test
	void accountType_없는_기존_요청도_null로_하위_호환_동작한다() throws Exception {
		service.result = new BrandRegistrationService.Result(42L, "brandx", 100L, false);

		mvc.perform(post("/api/brands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"brandx\"}"))
				.andExpect(status().isCreated());

		assertThat(service.receivedAccountType).isNull();
	}

	@Test
	void own_link_PUT은_절대값을_그대로_리포지토리에_전달하고_204다() throws Exception {
		mvc.perform(put("/api/brands/brandx/own-link").contentType(MediaType.APPLICATION_JSON)
						.content("{\"hasOwnLink\": false}"))
				.andExpect(status().isNoContent());

		assertThat(brands.setHasOwnLinkUsername).isEqualTo("brandx");
		assertThat(brands.setHasOwnLinkValue).isFalse();
	}

	@Test
	void own_link_PUT_true도_그대로_전달된다() throws Exception {
		mvc.perform(put("/api/brands/brandx/own-link").contentType(MediaType.APPLICATION_JSON)
						.content("{\"hasOwnLink\": true}"))
				.andExpect(status().isNoContent());

		assertThat(brands.setHasOwnLinkValue).isTrue();
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

	// ---------- direct 게시물 명령(2026-08-18 direct 통합 §2-2·§2-4·§4-2) ----------

	@Test
	void direct_등록_신규_수집은_201이다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);
		directCollect.result = samplePost("ABC123", 1754000000L);

		mvc.perform(post("/api/brands/1/direct-posts").contentType(MediaType.APPLICATION_JSON)
						.content("{\"shortCode\":\"ABC123\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.shortCode").value("ABC123"))
				.andExpect(jsonPath("$.authorUsername").value("author1"))
				.andExpect(jsonPath("$.contentType").value("REELS"));
		assertThat(directCollect.receivedShortCode).isEqualTo("ABC123");
		assertThat(directCollect.receivedBrand.id()).isEqualTo(1L);
	}

	@Test
	void direct_등록_이미_등록된_행은_200_멱등이고_단건_콜을_다시_내지_않는다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);
		taggedPosts.snapshot = Optional.of(new TaggedPostRepository.DirectSnapshot("ABC123", "author1",
				Instant.ofEpochSecond(1754000000L), "REELS"));

		mvc.perform(post("/api/brands/1/direct-posts").contentType(MediaType.APPLICATION_JSON)
						.content("{\"shortCode\":\"ABC123\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shortCode").value("ABC123"));
		assertThat(directCollect.receivedShortCode).isNull();   // 재수집 콜이 안 나갔다
	}

	@Test
	void direct_등록_게시물_부재는_404_POST_NOT_FOUND다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);
		directCollect.toThrow = new SubjectNotFoundException("게시물 없음");

		mvc.perform(post("/api/brands/1/direct-posts").contentType(MediaType.APPLICATION_JSON)
						.content("{\"shortCode\":\"Ghost\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
	}

	@Test
	void direct_등록_게시일_미상은_422_POST_UNSUPPORTED다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);
		directCollect.toThrow = new PostShapeUnsupportedException("게시일 미상");

		mvc.perform(post("/api/brands/1/direct-posts").contentType(MediaType.APPLICATION_JSON)
						.content("{\"shortCode\":\"NoDate\"}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("POST_UNSUPPORTED"));
	}

	@Test
	void direct_등록_브랜드_미존재는_404_BRAND_NOT_FOUND다() throws Exception {
		brands.row = null;

		mvc.perform(post("/api/brands/1/direct-posts").contentType(MediaType.APPLICATION_JSON)
						.content("{\"shortCode\":\"ABC123\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BRAND_NOT_FOUND"));
	}

	@Test
	void direct_등록_비ACTIVE_브랜드는_404_BRAND_NOT_FOUND다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.CLOSED, null, 12, true);

		mvc.perform(post("/api/brands/1/direct-posts").contentType(MediaType.APPLICATION_JSON)
						.content("{\"shortCode\":\"ABC123\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BRAND_NOT_FOUND"));
	}

	@Test
	void direct_등록_shortCode_누락은_400이다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);

		mvc.perform(post("/api/brands/1/direct-posts").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION"));
	}

	@Test
	void direct_등록_importLegacyHistory가_true면_수집_전에_레거시_이력을_복사한다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);
		directCollect.result = samplePost("ABC123", 1754000000L);

		mvc.perform(post("/api/brands/1/direct-posts").contentType(MediaType.APPLICATION_JSON)
						.content("{\"shortCode\":\"ABC123\",\"importLegacyHistory\":true,"
								+ "\"registeredAt\":\"2026-08-01T00:00:00Z\"}"))
				.andExpect(status().isCreated());

		assertThat(legacyHistoryCopier.copiedShortCode).isEqualTo("ABC123");
		assertThat(directCollect.receivedRegisteredAt).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
	}

	@Test
	void direct_등록_importLegacyHistory가_없으면_레거시_복사를_건너뛴다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);
		directCollect.result = samplePost("ABC123", 1754000000L);

		mvc.perform(post("/api/brands/1/direct-posts").contentType(MediaType.APPLICATION_JSON)
						.content("{\"shortCode\":\"ABC123\"}"))
				.andExpect(status().isCreated());

		assertThat(legacyHistoryCopier.copiedShortCode).isNull();
	}

	@Test
	void direct_취소_행이_없어도_204_멱등이다() throws Exception {
		taggedPosts.deleteIfDirectOnlyResult = false;   // 행 자체가 없음 — delete·clear 둘 다 no-op

		mvc.perform(delete("/api/brands/1/direct-posts/{shortCode}", "ABC123")).andExpect(status().isNoContent());

		assertThat(taggedPosts.deleteIfDirectOnlyBrandId).isEqualTo(1L);
		assertThat(taggedPosts.clearDirectBrandId).isEqualTo(1L);   // 무해한 no-op UPDATE
	}

	@Test
	void direct_취소_겹침_행은_direct_표식만_해제하고_204다() throws Exception {
		taggedPosts.deleteIfDirectOnlyResult = false;   // tag_detected_at 있음 — delete 조건 불충족

		mvc.perform(delete("/api/brands/1/direct-posts/{shortCode}", "ABC123")).andExpect(status().isNoContent());

		assertThat(taggedPosts.clearDirectBrandId).isEqualTo(1L);
		assertThat(taggedPosts.clearDirectShortCode).isEqualTo("ABC123");
	}

	@Test
	void direct_취소_순수_direct_행은_삭제되고_204이며_clearDirect는_불리지_않는다() throws Exception {
		taggedPosts.deleteIfDirectOnlyResult = true;   // tag_detected_at 없음 — delete 성공

		mvc.perform(delete("/api/brands/1/direct-posts/{shortCode}", "ABC123")).andExpect(status().isNoContent());

		assertThat(taggedPosts.deleteIfDirectOnlyShortCode).isEqualTo("ABC123");
		assertThat(taggedPosts.clearDirectBrandId).isNull();   // 이미 지워졌으니 clearDirect는 호출 안 됨
	}

	// ---------- 태그 셋 관리(유저 입력, 2026-08-12) ----------

	@Test
	void 태그_조회는_현재_활성_태그_목록을_돌려준다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);
		hashtags.tags = List.of("cclime", "끌리메");

		mvc.perform(get("/api/brands/brandx/hashtag-tags"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tags[0]").value("cclime"))
				.andExpect(jsonPath("$.tags[1]").value("끌리메"));
	}

	// ---------- 태그별 스윕 실행 상태(FE 요청, 2026-08-31) ----------

	@Test
	void 실행_상태_조회는_태그별_status를_계산해_돌려준다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);
		OffsetDateTime finishedAt = OffsetDateTime.now().minusMinutes(5);
		hashtags.runStates = List.of(
				new BrandHashtagRepository.RunStateRow("cclime", null, finishedAt, 3, false),
				new BrandHashtagRepository.RunStateRow("끌리메", null, null, null, false));

		mvc.perform(get("/api/brands/brandx/hashtag-tags/run-state"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tags[0].tag").value("cclime"))
				.andExpect(jsonPath("$.tags[0].status").value("done"))
				.andExpect(jsonPath("$.tags[0].lastFoundCount").value(3))
				.andExpect(jsonPath("$.tags[1].tag").value("끌리메"))
				.andExpect(jsonPath("$.tags[1].status").value("collecting"))
				.andExpect(jsonPath("$.tags[1].lastRunAt").value(nullValue()));
	}

	@Test
	void 실행_상태_조회는_미등록_브랜드면_404다() throws Exception {
		mvc.perform(get("/api/brands/ghost/hashtag-tags/run-state"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BRAND_NOT_FOUND"));
	}

	@Test
	void 태그_교체는_정규화_후_전체_교체한다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);

		// # 제거 · 대소문자 통일 · 중복 제거(입력 순서 보존)
		mvc.perform(put("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\":[\"#CClime\",\" cclime \",\"NewTag\"]}"))
				.andExpect(status().isNoContent());

		assertThat(hashtags.receivedTagsBrandId).isEqualTo(1L);
		assertThat(hashtags.receivedTags).containsExactly("cclime", "newtag");
	}

	@Test
	void 태그_재추가와_삭제_시나리오는_정규화된_전체_목록을_그대로_전달한다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);
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
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);

		mvc.perform(put("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\":[\"  \",\"\"]}"))
				.andExpect(status().isNoContent());

		assertThat(hashtags.receivedTags).isEmpty();
	}

	@Test
	void 태그_null_바디는_빈_목록_교체로_허용된다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);

		mvc.perform(put("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isNoContent());

		assertThat(hashtags.receivedTags).isEmpty();
	}

	// ---------- 태그 등록 시 즉시 스윕 트리거(2026-08-17) ----------

	@Test
	void 태그_교체가_비어있지_않으면_즉시_스윕을_트리거한다() throws Exception {
		brands.row = new BrandRow(7L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);

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
		brands.row = new BrandRow(7L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);

		mvc.perform(put("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isNoContent());

		assertThat(service.triggeredSweepBrandId).isEqualTo(7L);
		assertThat(service.triggeredSweepTags).isEmpty();
	}

	// ---------- 태그 단건 추가·삭제(2026-08-12, 표준 REST 확장) ----------

	@Test
	void 태그_추가는_정규화_검증_후_전달한다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);

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
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);

		mvc.perform(delete("/api/brands/brandx/hashtag-tags/{tag}", "cclime"))
				.andExpect(status().isNoContent());

		assertThat(service.triggeredSweepBrandId).isNull();
	}

	@Test
	void 태그_전체_삭제는_스윕을_트리거하지_않는다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);

		mvc.perform(delete("/api/brands/brandx/hashtag-tags"))
				.andExpect(status().isNoContent());

		assertThat(service.triggeredSweepBrandId).isNull();
	}

	/** POST는 PUT과 달리 빈 입력을 422로 거부한다 — "추가할 게 없다"는 요청 자체가 실수일 확률이 높다. */
	@Test
	void 태그_추가는_빈_입력이면_422다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);

		mvc.perform(post("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("VALIDATION"));

		assertThat(hashtags.addedTags).isNull();
	}

	@Test
	void 태그_추가는_무효_문자면_422다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);

		mvc.perform(post("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\":[\"tag.dot\"]}"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("VALIDATION"));

		assertThat(hashtags.addedTags).isNull();
	}

	@Test
	void 태그_단건_삭제는_정규화_후_전달하고_204다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);

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
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);

		mvc.perform(delete("/api/brands/brandx/hashtag-tags/{tag}", "끌리메"))
				.andExpect(status().isNoContent());

		assertThat(hashtags.deletedTagBrandId).isEqualTo(1L);
		assertThat(hashtags.deletedTag).isEqualTo("끌리메");
	}

	@Test
	void 태그_전체_삭제는_204다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);

		mvc.perform(delete("/api/brands/brandx/hashtag-tags"))
				.andExpect(status().isNoContent());

		assertThat(hashtags.deletedAllTagsBrandId).isEqualTo(1L);
	}

	/** 유효 문자 위반은 절삭이 아니라 거부 — 공백(끌리 메)·점(tag.dot) 모두 VALID_TAG 전체 일치 실패. */
	@Test
	void 유효_문자_위반_태그는_절삭하지_않고_422로_거부한다() throws Exception {
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.ACTIVE, null, 12, true);

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
		brands.row = new BrandRow(1L, "brandx", "ig1", BrandStatus.CLOSED, null, 12, true);

		mvc.perform(get("/api/brands/brandx/hashtag-tags"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BRAND_NOT_FOUND"));
		mvc.perform(put("/api/brands/brandx/hashtag-tags").contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\":[\"a\"]}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BRAND_NOT_FOUND"));
	}

}
