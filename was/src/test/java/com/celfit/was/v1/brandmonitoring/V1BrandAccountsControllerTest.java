package com.celfit.was.v1.brandmonitoring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.auth.UserProfile;
import com.celfit.was.auth.UserRepository;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.MonitoringApiException;
import com.celfit.was.monitoring.MonitoringCommandClient;
import com.celfit.was.monitoring.MonitoringUnavailableException;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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

/**
 * /v1/brand-monitoring/accounts 계약(스펙 §5-1·§5-2·§5-3, 08-07 다계정 개정) — 연결 멱등·한도
 * 409/422/503 분기, 상태 유도(collecting/ready/error), 소유권 403, 삭제의 monitoring 탈퇴 조건을
 * 고정한다. POST는 "브랜드 연결"이다: 이미 연결된 계정 재요청은 기존 객체 반환(멱등)이고,
 * BRAND_ACCOUNT_LIMIT_REACHED만 409다(IMMUTABLE·ALREADY_EXISTS는 폐기).
 * V1CampaignControllerTest 관용구: SecurityConfig·V1ExceptionAdvice Import, DB·monitoring 접점은 mock.
 * 서비스·트랜잭션·어셈블러는 실 빈으로 붙여 플로우 전체가 실제로 돌게 한다(슬라이스에는 트랜잭션
 * 매니저가 없어 @Transactional은 무력하지만, 호출 순서·멱등·한도 판정 로직은 그대로 검증된다).
 */
@WebMvcTest(controllers = V1BrandAccountsController.class,
		properties = {"was.cors.allowed-origins=http://localhost:3000", "monitoring.enabled=true"})
@Import({V1BrandAccountService.class, BrandLinkTransaction.class, BrandAccountAssembler.class,
		V1ExceptionAdvice.class, SecurityConfig.class})
class V1BrandAccountsControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	BrandLinkRepository linkRepository;
	@MockitoBean
	MonitoringCommandClient commandClient;
	@MockitoBean
	BrandReadRepository brandReadRepository;
	@MockitoBean
	UserRepository userRepository;

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	/** brandName 전달 검증용 — UserProfile 필드 중 이 테스트가 실제 쓰는 것만 채운다. */
	private static UserProfile profileOf(String userType, String companyName) {
		return new UserProfile(7L, "user@example.com", "테스트유저", null, userType, "EMAIL", null, null,
				companyName, null, null, null, false, null, null,
				OffsetDateTime.parse("2026-06-01T00:00:00Z"), "USER");
	}

	private static BrandLinkRow link(long userId, long brandId) {
		return link(userId, brandId, "lizda_official");
	}

	private static BrandLinkRow link(long userId, long brandId, String username) {
		return link(userId, brandId, username, BrandAccountType.OWN);
	}

	private static BrandLinkRow link(long userId, long brandId, String username, String accountType) {
		return link(userId, brandId, username, accountType, 12);
	}

	private static BrandLinkRow link(long userId, long brandId, String username, String accountType, int months) {
		return new BrandLinkRow(brandId, userId, brandId, username, accountType, months,
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), null);
	}

	/** 한도 검증용 — 서로 다른 브랜드 n개에 연결된 상태(요청 계정명과 겹치지 않는 이름). */
	private static List<BrandLinkRow> links(int count, String accountType) {
		List<BrandLinkRow> links = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			links.add(link(7L, 200L + i, "other_brand_" + i, accountType));
		}
		return links;
	}

	/** 첫 수집 진행 중 — 스윕 완주 사실(last_swept_at)이 아예 없다. backfill_error null. */
	private static BrandAccountRow collectingRow(long brandId, String username) {
		return new BrandAccountRow(brandId, username, null, null,
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), null, null,
				30876L, 12L, 340L, "브랜드 소개", "리즈다", "https://cdn/pic.jpg", true, "https://lizda.co.kr", "ACTIVE", null,
				12, OffsetDateTime.parse("2026-08-01T00:00:00Z"));
	}

	/** 백필 리셋·재가입·스윕 실패 — last_swept_on은 null이지만 지난 스윕 완주 사실이 있다. */
	private static BrandAccountRow sweptFactRow(long brandId, String backfillError) {
		return new BrandAccountRow(brandId, "lizda_official", null, OffsetDateTime.parse("2026-07-01T00:00:00Z"),
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), null, backfillError,
				30876L, 12L, 340L, null, null, "https://cdn/pic.jpg", null, null, "ACTIVE", null,
				12, OffsetDateTime.parse("2026-08-01T00:00:00Z"));
	}

	private static BrandAccountRow readyRow(long brandId) {
		return new BrandAccountRow(brandId, "lizda_official", LocalDate.of(2026, 8, 7),
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), OffsetDateTime.parse("2026-08-01T00:00:00Z"),
				OffsetDateTime.parse("2026-08-01T01:00:00Z"), null,
				30876L, 12L, 340L, null, null, "https://cdn/pic.jpg", null, null, "ACTIVE", null,
				12, OffsetDateTime.parse("2026-08-01T00:00:00Z"));
	}

	private static BrandAccountRow errorRow(long brandId) {
		return new BrandAccountRow(brandId, "lizda_official", null, null,
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), null, "초기 수집에 실패했어요. 자동으로 재시도 중이에요.",
				null, null, null, null, null, null, null, null, "ACTIVE", null,
				12, OffsetDateTime.parse("2026-08-01T00:00:00Z"));
	}

	/**
	 * 확장 수집 진행 — last_swept_on과 완주 시각(backfill_completed_at)이 둘 다 비었고(08-13 개정:
	 * expandWindow가 완주 시각도 리셋한다) 스윕 완주 사실(last_swept_at)만 남아 데이터는 서빙 중.
	 */
	private static BrandAccountRow expandingRow(long brandId) {
		return new BrandAccountRow(brandId, "lizda_official", null,
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), OffsetDateTime.parse("2026-08-01T00:00:00Z"),
				null, null,
				30876L, 12L, 340L, null, null, "https://cdn/pic.jpg", null, null, "ACTIVE", null,
				6, OffsetDateTime.parse("2026-08-12T10:00:00Z"));
	}

	/** 확장 게이트 검증용 — 자산 창(collection_months)만 파라미터로 바꾼 완주 상태 행. */
	private static BrandAccountRow expandingRowMonths(long brandId, int months) {
		return new BrandAccountRow(brandId, "lizda_official", LocalDate.of(2026, 8, 7),
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), OffsetDateTime.parse("2026-08-01T00:00:00Z"),
				OffsetDateTime.parse("2026-08-01T01:00:00Z"), null,
				30876L, 12L, 340L, null, null, "https://cdn/pic.jpg", null, null, "ACTIVE", null,
				months, OffsetDateTime.parse("2026-08-01T00:00:00Z"));
	}

	// ---------- 연결 ----------

	@Test
	void 연결은_202와_collecting_계정을_반환한다() throws Exception {
		given(commandClient.registerBrand("lizda_official", null, 12))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));
		// 등록 응답은 단건 조회(get)를 거친다 — 방금 만든 연결을 다시 읽으므로 실서비스에선 항상 존재한다.
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"@Lizda_Official\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.id").value("100"))
				.andExpect(jsonPath("$.data.collectionStatus").value("collecting"))
				.andExpect(jsonPath("$.data.profile.username").value("lizda_official"))
				.andExpect(jsonPath("$.data.profile.profileUrl").value("https://www.instagram.com/lizda_official/"))
				.andExpect(jsonPath("$.data.collectionCompletedAt").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.data.collectionError").value(Matchers.nullValue()));

		then(linkRepository).should().insertLink(7L, 100L, "lizda_official", BrandAccountType.OWN, 12);
	}

	/** image_object_path(monitoring 자체 아카이브 결과)가 있으면 원본 CDN URL보다 /img/ 상대경로를 우선 서빙한다. */
	@Test
	void 아카이브된_브랜드_프로필_이미지는_img_상대경로를_서빙한다() throws Exception {
		given(commandClient.registerBrand("lizda_official", null, 12))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(
				new BrandAccountRow(100L, "lizda_official", null, null,
						OffsetDateTime.parse("2026-08-07T00:00:00Z"), null, null,
						30876L, 12L, 340L, "브랜드 소개", "리즈다", "https://cdn/pic.jpg", true,
						"https://lizda.co.kr", "ACTIVE", "monitor-brand/56161796372.jpg",
						12, OffsetDateTime.parse("2026-08-07T00:00:00Z"))));
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.profile.profilePicUrl").value("/img/monitor-brand/56161796372.jpg"));
	}

	// ---------- brandName 전달(스펙 2026-08-11 §2) ----------

	@Test
	void brand_유형_유저의_등록은_company_name을_brandName으로_전달한다() throws Exception {
		given(userRepository.findProfileById(7L)).willReturn(Optional.of(profileOf("brand", "끌리메")));
		given(commandClient.registerBrand("lizda_official", "끌리메", 12))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isAccepted());

		then(commandClient).should().registerBrand("lizda_official", "끌리메", 12);
	}

	@Test
	void 비brand_유형은_brandName_없이_전달한다() throws Exception {
		given(userRepository.findProfileById(7L)).willReturn(Optional.of(profileOf("agency", "대행사명")));
		given(commandClient.registerBrand("lizda_official", null, 12))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isAccepted());

		then(commandClient).should().registerBrand("lizda_official", null, 12);
	}

	@Test
	void company_name이_빈_문자열이면_null로_전달한다() throws Exception {
		given(userRepository.findProfileById(7L)).willReturn(Optional.of(profileOf("brand", "")));
		given(commandClient.registerBrand("lizda_official", null, 12))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isAccepted());

		then(commandClient).should().registerBrand("lizda_official", null, 12);
	}

	@Test
	void 프로필_조회_실패여도_등록은_진행된다() throws Exception {
		given(userRepository.findProfileById(7L)).willReturn(Optional.empty());
		given(commandClient.registerBrand("lizda_official", null, 12))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isAccepted());

		then(commandClient).should().registerBrand("lizda_official", null, 12);
	}

	@Test
	void 스윕_완주_사실이_있으면_백필_리셋_중에도_ready로_기존_데이터를_노출한다() throws Exception {
		// 정책 리셋(last_swept_on=NULL)·재가입·스윕 실패가 겹쳐도, 한 번이라도 완주한 계정은
		// 기존 데이터가 서빙 가능하므로 collecting(로딩)이 아니라 ready다 — 08-10 결정.
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(sweptFactRow(100L, null)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.collectionStatus").value("ready"))
				.andExpect(jsonPath("$.data.lastDetectedAt").value("2026-07-01T09:00:00+09:00"))
				.andExpect(jsonPath("$.data.lastTrackedAt").value("2026-07-01T09:00:00+09:00"));
	}

	@Test
	void 스윕_완주_사실이_있으면_backfill_error가_있어도_ready가_이긴다() throws Exception {
		// 재가입 백필 실패로 backfill_error가 남아도 기존 데이터가 있으면 에러 화면보다 데이터가 낫다.
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L))
				.willReturn(Optional.of(sweptFactRow(100L, "초기 수집에 실패했어요. 자동으로 재시도 중이에요.")));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.collectionStatus").value("ready"))
				.andExpect(jsonPath("$.data.collectionError").value(Matchers.nullValue()));
	}

	@Test
	void 이미_수집된_브랜드에_연결하면_재수집_없이_ready_객체를_돌려준다() throws Exception {
		// 핵심 요구 — 다른 사용자가 이미 등록한 브랜드는 monitoring replay(수집 재시작 없음)로
		// 같은 brandId를 받고, 연결 직후 기존 수집 상태(ready)가 그대로 보인다.
		given(commandClient.registerBrand("lizda_official", null, 12))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.id").value("100"))
				.andExpect(jsonPath("$.data.collectionStatus").value("ready"));

		then(linkRepository).should().insertLink(7L, 100L, "lizda_official", BrandAccountType.OWN, 12);
	}

	@Test
	void 이미_연결된_계정_재요청은_멱등_202로_기존_객체를_돌려준다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.id").value("100"))
				.andExpect(jsonPath("$.data.collectionStatus").value("ready"));

		then(commandClient).should(never()).registerBrand(anyString(), any(), anyInt());
		then(linkRepository).should(never()).insertLink(anyLong(), anyLong(), anyString(), anyString(), anyInt());
	}

	@Test
	void 다른_브랜드가_연결돼_있어도_추가_연결한다() throws Exception {
		// 구 계약의 BRAND_ACCOUNT_IMMUTABLE·ALREADY_EXISTS 지점 — 다계정 개정으로 정상 연결이다.
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link(7L, 200L, "other_brand")));
		given(commandClient.registerBrand("lizda_official", null, 12))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.id").value("100"));

		then(linkRepository).should().insertLink(7L, 100L, "lizda_official", BrandAccountType.OWN, 12);
	}

	@Test
	void own이_6개면_409_BRAND_ACCOUNT_LIMIT_REACHED이고_monitoring을_호출하지_않는다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(links(6, BrandAccountType.OWN));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"new_brand\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("BRAND_ACCOUNT_LIMIT_REACHED"));

		then(commandClient).should(never()).registerBrand(anyString(), any(), anyInt());
	}

	@Test
	void competitor가_3개면_409_COMPETITOR_ACCOUNT_LIMIT_REACHED다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(links(3, BrandAccountType.COMPETITOR));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"new_brand\",\"accountType\":\"competitor\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("COMPETITOR_ACCOUNT_LIMIT_REACHED"));

		then(commandClient).should(never()).registerBrand(anyString(), any(), anyInt());
	}

	@Test
	void own_6개가_차도_competitor는_등록된다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(links(6, BrandAccountType.OWN));
		given(commandClient.registerBrand("rival_brand", null, 12))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(300L, "rival_brand", 30876L, "ACTIVE"));
		given(brandReadRepository.findAccount(300L)).willReturn(Optional.of(readyRow(300L)));
		given(linkRepository.findActiveByUserAndBrand(7L, 300L))
				.willReturn(Optional.of(link(7L, 300L, "rival_brand", BrandAccountType.COMPETITOR)));

		var result = mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"rival_brand\",\"accountType\":\"competitor\"}"))
				.andExpect(status().isAccepted());

		// 응답 필드는 mock 조회분을 되읽는 것이라 실제 저장 타입을 증명하지 못한다 — 저장 인자를 직접 고정한다.
		then(linkRepository).should().insertLink(7L, 300L, "rival_brand", BrandAccountType.COMPETITOR, 12);

		result.andExpect(jsonPath("$.data.accountType").value("competitor"));
	}

	@Test
	void competitor_연결_등록은_brandName_없이_전달한다() throws Exception {
		// #406 경쟁사 계정 타입 게이트 — brandNameOf(userId)는 유저 자신의 회사명이라, 그대로
		// competitor 연결에 넘기면 남의(경쟁사) 브랜드에 내 회사명이 해시태그로 시드된다. own 유형
		// 유저(brandName이 실재)라도 accountType=competitor면 registerBrand에 null이 가야 한다.
		given(userRepository.findProfileById(7L)).willReturn(Optional.of(profileOf("brand", "끌리메")));
		given(commandClient.registerBrand("rival_brand", null, 12))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(300L, "rival_brand", 30876L, "ACTIVE"));
		given(brandReadRepository.findAccount(300L)).willReturn(Optional.of(readyRow(300L)));
		given(linkRepository.findActiveByUserAndBrand(7L, 300L))
				.willReturn(Optional.of(link(7L, 300L, "rival_brand", BrandAccountType.COMPETITOR)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"rival_brand\",\"accountType\":\"competitor\"}"))
				.andExpect(status().isAccepted());

		then(commandClient).should().registerBrand("rival_brand", null, 12);
	}

	@Test
	void 이미_연결된_계정을_다른_타입으로_재요청하면_재수집_없이_타입만_바꾼다() throws Exception {
		// 08-12 FE UX — "이미 등록된 계정을 경쟁사로 다시 넣으면 옮겨진다"가 409가 아니라 정상 경로다.
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));
		given(linkRepository.findActiveByUserAndBrand(7L, 100L))
				.willReturn(Optional.of(link(7L, 100L, "lizda_official", BrandAccountType.COMPETITOR)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"lizda_official\",\"accountType\":\"competitor\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.id").value("100"));

		then(linkRepository).should().updateAccountType(7L, 100L, BrandAccountType.COMPETITOR);
		// 이 분기는 precheck 안에서 커밋까지 끝나 뒤에 link()의 재확인이 없다 — 유저 잠금을 잡지
		// 않으면 동시 요청 둘이 같은 잔여 자리를 보고 둘 다 통과해 상한을 영구히 넘긴다(복구 불가).
		// 슬라이스에서 잠금 자체의 효과는 관측할 수 없으므로 호출 사실을 고정한다.
		then(linkRepository).should().lockUser(7L);
		then(commandClient).should(never()).registerBrand(anyString(), any(), anyInt());
	}

	@Test
	void 재등록으로_타입을_바꾸면_monitoring을_호출하지_않는다() throws Exception {
		// 위 테스트와 같은 경로지만 응답 필드(accountType)까지 고정한다 — 재등록 응답도 바뀐 타입을 실어야 한다.
		given(linkRepository.findAllActiveByUser(7L))
				.willReturn(List.of(link(7L, 10L, "my_brand", BrandAccountType.OWN)));
		given(linkRepository.findActiveByUserAndBrand(7L, 10L))
				.willReturn(Optional.of(link(7L, 10L, "my_brand", BrandAccountType.COMPETITOR)));
		given(brandReadRepository.findAccount(10L)).willReturn(Optional.of(readyRow(10L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"my_brand\",\"accountType\":\"competitor\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.accountType").value("competitor"));

		then(linkRepository).should().updateAccountType(7L, 10L, "competitor");
		then(commandClient).should(never()).registerBrand(anyString(), any(), anyInt());
	}

	@Test
	void competitor가_3개면_기존_계정의_타입_변경도_409고_바꾸지_않는다() throws Exception {
		List<BrandLinkRow> existing = new ArrayList<>(links(3, BrandAccountType.COMPETITOR));
		existing.add(link(7L, 100L));   // own인 lizda_official — 이걸 competitor로 옮기려는 요청이다.
		given(linkRepository.findAllActiveByUser(7L)).willReturn(existing);

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"lizda_official\",\"accountType\":\"competitor\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("COMPETITOR_ACCOUNT_LIMIT_REACHED"));

		then(linkRepository).should(never()).updateAccountType(anyLong(), anyLong(), anyString());
	}

	@Test
	void 값_공간_밖의_accountType은_400이고_monitoring을_호출하지_않는다() throws Exception {
		// DB CHECK 위반이 500으로 새지 않도록 서비스 층에서 먼저 막는다(계정명은 일부러 정상값).
		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"lizda_official\",\"accountType\":\"rival\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(commandClient).should(never()).registerBrand(anyString(), any(), anyInt());
		then(linkRepository).should(never()).insertLink(anyLong(), anyLong(), anyString(), anyString(), anyInt());
	}

	// ---------- 수집 범위(collectionMonths, 2026-08-12 FE 요청서) ----------

	@Test
	void 값_공간_밖_collectionMonths는_400이다() throws Exception {
		// accountType과 같은 이유 — CHECK 제약 위반이 500으로 새기 전에 서비스 층에서 막는다.
		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\", \"collectionMonths\": 2}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(commandClient).should(never()).registerBrand(anyString(), any(), anyInt());
	}

	@Test
	void 신규_등록은_collectionMonths를_monitoring에_전달한다() throws Exception {
		given(commandClient.registerBrand("lizda_official", null, 3))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\", \"collectionMonths\": 3}"))
				.andExpect(status().isAccepted());

		then(commandClient).should().registerBrand("lizda_official", null, 3);
		// 신청값은 자산(monitoring)뿐 아니라 연결 행에도 그대로 남는다(2026-08-17) — 유저별 표시 창의 정본.
		then(linkRepository).should().insertLink(7L, 100L, "lizda_official", BrandAccountType.OWN, 3);
	}

	@Test
	void 이미_연결된_계정의_더_큰_창_재등록은_확장으로_monitoring을_재호출한다() throws Exception {
		// 자산 창 6 < 요청 12 — 멱등 경로라도 확장은 monitoring 재호출이 필요하다(정본 판정은 monitoring replay).
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(expandingRowMonths(100L, 6)));
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(commandClient.registerBrand("lizda_official", null, 12))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\", \"collectionMonths\": 12}"))
				.andExpect(status().isAccepted());

		then(commandClient).should().registerBrand("lizda_official", null, 12);
		// 확장은 자산과 링크 둘 다에 반영된다(2026-08-17) — 새 연결은 아니므로 insertLink는 없다.
		then(linkRepository).should().updateCollectionMonths(7L, 100L, 12);
		then(linkRepository).should(never()).insertLink(anyLong(), anyLong(), anyString(), anyString(), anyInt());
	}

	@Test
	void competitor_확장_재호출도_brandName_없이_전달한다() throws Exception {
		// #406 경쟁사 계정 타입 게이트는 신규 등록뿐 아니라 확장 재호출에도 걸려야 한다 — brand 유형
		// 유저(company_name 실재)의 competitor 확장에 회사명을 실으면 남의 브랜드 해시태그 셋이
		// 오염되고, 그 브랜드를 공유하는 모든 사용자에게 퍼져 SQL 외 복구가 불가능하다.
		given(userRepository.findProfileById(7L)).willReturn(Optional.of(profileOf("brand", "끌리메")));
		given(linkRepository.findAllActiveByUser(7L))
				.willReturn(List.of(link(7L, 100L, "lizda_official", BrandAccountType.COMPETITOR)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(expandingRowMonths(100L, 6)));
		given(linkRepository.findActiveByUserAndBrand(7L, 100L))
				.willReturn(Optional.of(link(7L, 100L, "lizda_official", BrandAccountType.COMPETITOR)));
		given(commandClient.registerBrand("lizda_official", null, 12))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\", \"accountType\": \"competitor\","
								+ " \"collectionMonths\": 12}"))
				.andExpect(status().isAccepted());

		then(commandClient).should().registerBrand("lizda_official", null, 12);
	}

	@Test
	void 재등록이_명시한_collectionMonths는_링크에_그대로_반영된다_축소_허용() throws Exception {
		// 이미 연결됨(자산 12) + 3개월 재-POST → 자산은 불변(monitoring 콜 0), 링크만 3으로 갱신.
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link(7L, 100L)));
		given(linkRepository.findActiveByUserAndBrand(7L, 100L))
				.willReturn(Optional.of(link(7L, 100L, "lizda_official", BrandAccountType.OWN, 3)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\", \"collectionMonths\": 3}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.collectionMonths").value(3));   // 링크 값 — 자산(12) 아님

		then(linkRepository).should().updateCollectionMonths(7L, 100L, 3);
		then(commandClient).should(never()).registerBrand(anyString(), any(), anyInt());
	}

	@Test
	void 개명된_브랜드_재등록도_명시한_collectionMonths를_링크에_반영한다() throws Exception {
		// precheck는 링크의 username 사본으로 비교하므로 IG 개명 후 새 이름은 멱등 경로에 걸리지 않는다.
		// 그러면 monitoring 등록이 같은 brandId로 replay돼 link()의 기존 연결 분기로 접히는데,
		// 그 분기가 months를 무시하면 유저가 명시한 3개월이 조용히 사라진다(링크는 12로 남는다).
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link(7L, 100L, "old_name")));
		given(commandClient.registerBrand("new_name", null, 3))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "new_name", 30876L, "ACTIVE"));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));
		given(linkRepository.findActiveByUserAndBrand(7L, 100L))
				.willReturn(Optional.of(link(7L, 100L, "old_name", BrandAccountType.OWN, 3)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"new_name\", \"collectionMonths\": 3}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.collectionMonths").value(3));

		then(linkRepository).should().updateCollectionMonths(7L, 100L, 3);
		// 이미 있는 연결이라 새 행을 만들지는 않는다(멱등).
		then(linkRepository).should(never()).insertLink(anyLong(), anyLong(), anyString(), anyString(), anyInt());
	}

	@Test
	void 재등록이_collectionMonths를_생략하면_링크_기간은_불변이다() throws Exception {
		// 구 클라이언트의 필드 없는 재-POST가 3개월 링크를 12로 되돌리면 안 된다.
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link(7L, 100L)));
		given(linkRepository.findActiveByUserAndBrand(7L, 100L))
				.willReturn(Optional.of(link(7L, 100L, "lizda_official", BrandAccountType.OWN, 3)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isAccepted());

		then(linkRepository).should(never()).updateCollectionMonths(anyLong(), anyLong(), anyInt());
	}

	@Test
	void 형식_위반은_400이고_monitoring을_호출하지_않는다() throws Exception {
		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"https://www.instagram.com/lizda_official/\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(commandClient).should(never()).registerBrand(anyString(), any(), anyInt());
	}

	@Test
	void monitoring_404는_422_INSTAGRAM_ACCOUNT_NOT_FOUND로_번역된다() throws Exception {
		given(commandClient.registerBrand("lizda_official", null, 12))
				.willThrow(new MonitoringApiException("SUBJECT_NOT_FOUND", "인스타그램에서 계정·게시물을 찾을 수 없습니다.", 404));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.error.code").value("INSTAGRAM_ACCOUNT_NOT_FOUND"));
	}

	@Test
	void monitoring_422_비공개는_422_PRIVATE_ACCOUNT로_전달된다() throws Exception {
		given(commandClient.registerBrand("lizda_official", null, 12))
				.willThrow(new MonitoringApiException("PRIVATE_ACCOUNT", "비공개 계정이라 수집할 수 없습니다.", 422));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.error.code").value("PRIVATE_ACCOUNT"))
				// monitoring 원문은 "[CODE] 메시지"로 감싸져 있어 그대로 흘리면 코드가 노출된다.
				.andExpect(jsonPath("$.error.message").value(Matchers.not(Matchers.containsString("["))));
	}

	@Test
	void monitoring_불능은_503_SERVICE_UNAVAILABLE이다() throws Exception {
		given(commandClient.registerBrand("lizda_official", null, 12))
				.willThrow(new MonitoringUnavailableException("연결 실패", null));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
				// 기존 monitoring 엔드포인트 4곳과 같은 계약 — 재시도 시점을 헤더로 준다.
				.andExpect(header().string("Retry-After", "5"));
	}

	@Test
	void 동시_같은_연결_경합은_멱등_202다() throws Exception {
		// (유저, 브랜드) 활성 유니크가 잡은 동시 같은 요청 — 원하는 상태는 이미 성립했으므로 성공으로 접는다.
		given(commandClient.registerBrand("lizda_official", null, 12))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(linkRepository.insertLink(7L, 100L, "lizda_official", BrandAccountType.OWN, 12))
				.willThrow(new DuplicateKeyException("brand_monitorings_active_user_brand_uidx"));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.id").value("100"));

		then(commandClient).should(never()).deregisterBrand(anyString());
	}

	@Test
	void 한도_경합이면_보상_탈퇴를_호출하고_409를_돌려준다() throws Exception {
		// 사전 확인 시점엔 여유가 있었지만 저장 트랜잭션의 잠금 재확인에서 한도가 찬 경합 경로 —
		// monitoring 등록은 이미 끝났으므로 다른 활성 사용자가 없으면 보상 탈퇴한다.
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(), links(6, BrandAccountType.OWN));
		given(commandClient.registerBrand("lizda_official", null, 12))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(linkRepository.countActiveByBrand(100L)).willReturn(0);

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("BRAND_ACCOUNT_LIMIT_REACHED"));

		then(commandClient).should().deregisterBrand("lizda_official");
	}

	@Test
	void 연결_보상은_다른_활성_연결이_남아있으면_호출하지_않는다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(), links(6, BrandAccountType.OWN));
		given(commandClient.registerBrand("lizda_official", null, 12))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(linkRepository.countActiveByBrand(100L)).willReturn(1);

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isConflict());

		then(commandClient).should(never()).deregisterBrand(anyString());
	}

	// ---------- 목록·단건 ----------

	@Test
	void 목록은_계정_없으면_빈_배열과_total_0이다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of());

		mockMvc.perform(get("/v1/brand-monitoring/accounts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(0))
				.andExpect(jsonPath("$.meta.total").value(0))
				// limit은 호환용으로 남긴 합산 최대(own 6 + competitor 3) — 실제 게이트는 limits·counts다.
				.andExpect(jsonPath("$.meta.limit").value(9))
				.andExpect(jsonPath("$.meta.counts.own").value(0))
				.andExpect(jsonPath("$.meta.counts.competitor").value(0));
	}

	@Test
	void 목록은_활성_연결의_계정을_돌려준다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].id").value("100"))
				.andExpect(jsonPath("$.data[0].collectionStatus").value("ready"))
				.andExpect(jsonPath("$.meta.total").value(1));
	}

	@Test
	void 목록은_다계정을_연결_순으로_전부_돌려준다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L))
				.willReturn(List.of(link(7L, 100L), link(7L, 200L, "other_brand")));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));
		given(brandReadRepository.findAccount(200L)).willReturn(Optional.of(collectingRow(200L, "other_brand")));

		mockMvc.perform(get("/v1/brand-monitoring/accounts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[0].id").value("100"))
				.andExpect(jsonPath("$.data[1].id").value("200"))
				.andExpect(jsonPath("$.data[1].collectionStatus").value("collecting"))
				.andExpect(jsonPath("$.meta.total").value(2));
	}

	@Test
	void 목록은_구독_타입을_그대로_돌려준다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(
				link(7L, 10L, "my_brand", BrandAccountType.OWN),
				link(7L, 11L, "rival_brand", BrandAccountType.COMPETITOR)));
		given(brandReadRepository.findAccount(10L)).willReturn(Optional.of(readyRow(10L)));
		given(brandReadRepository.findAccount(11L)).willReturn(Optional.of(readyRow(11L)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].accountType").value("own"))
				.andExpect(jsonPath("$.data[1].accountType").value("competitor"))
				.andExpect(jsonPath("$.meta.limits.own").value(6))
				.andExpect(jsonPath("$.meta.limits.competitor").value(3))
				.andExpect(jsonPath("$.meta.counts.own").value(1))
				.andExpect(jsonPath("$.meta.counts.competitor").value(1));
	}

	@Test
	void meta_counts는_목록이_아니라_연결_행에서_센다() throws Exception {
		// brand_account가 없어 목록에서 빠진 연결도 한도 자리는 차지한다(08-12 리뷰) — 목록에서 세면
		// FE가 "1 / 6"을 그려 놓고 다음 POST에서 409를 맞는다. total은 반환 목록 기준 그대로다.
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(
				link(7L, 10L, "my_brand", BrandAccountType.OWN),
				link(7L, 11L, "ghost_brand", BrandAccountType.OWN),
				link(7L, 12L, "rival_brand", BrandAccountType.COMPETITOR)));
		given(brandReadRepository.findAccount(10L)).willReturn(Optional.of(readyRow(10L)));
		given(brandReadRepository.findAccount(11L)).willReturn(Optional.empty());
		given(brandReadRepository.findAccount(12L)).willReturn(Optional.of(readyRow(12L)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.meta.total").value(2))
				.andExpect(jsonPath("$.meta.counts.own").value(2))
				.andExpect(jsonPath("$.meta.counts.competitor").value(1));
	}

	@Test
	void 단건은_ready_전이를_반영한다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.collectionStatus").value("ready"))
				.andExpect(jsonPath("$.data.collectionCompletedAt").value("2026-08-01T10:00:00+09:00"))
				.andExpect(jsonPath("$.data.lastDetectedAt").value("2026-08-07T09:00:00+09:00"))
				.andExpect(jsonPath("$.data.lastTrackedAt").value("2026-08-07T09:00:00+09:00"))
				// 08-12 정정: 운영 브랜드 스윕은 KST 02:00(서버 크론)이라 표기 기본값도 2다.
				.andExpect(jsonPath("$.data.nextScheduledAt").value(Matchers.endsWith("T02:00:00+09:00")))
				.andExpect(jsonPath("$.data.collectionError").value(Matchers.nullValue()))
				// 프로필 nullable 규칙 — fullName·biography는 "" 로, isVerified는 false로 접는다.
				.andExpect(jsonPath("$.data.profile.fullName").value(""))
				.andExpect(jsonPath("$.data.profile.biography").value(""))
				.andExpect(jsonPath("$.data.profile.isVerified").value(false))
				.andExpect(jsonPath("$.data.profile", Matchers.hasKey("externalUrl")))
				.andExpect(jsonPath("$.data.profile.externalUrl").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.data.profile.followerCount").value(30876));
	}

	@Test
	void 확장_중에는_ready로_기존_데이터를_서빙하고_완주_시각만_비운다() throws Exception {
		// 08-13 개정(08-12의 "확장 중 collecting"을 뒤집는다): 확장이 완주 시각을 리셋하므로
		// 진행 여부는 status가 아니라 collectionCompletedAt == null이 알린다(FE 폴링 종료 조건).
		// status는 collecting|ready|error 3값 고정이고, 데이터가 계속 서빙되므로 ready가 정확하다.
		// collectionMonths는 링크 값이 정본이라(2026-08-17) 자산 6과 같은 값을 링크에도 세팅해 둔다.
		given(linkRepository.findActiveByUserAndBrand(7L, 100L))
				.willReturn(Optional.of(link(7L, 100L, "lizda_official", BrandAccountType.OWN, 6)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(expandingRow(100L)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.collectionStatus").value("ready"))
				.andExpect(jsonPath("$.data", Matchers.hasKey("collectionCompletedAt")))
				.andExpect(jsonPath("$.data.collectionCompletedAt").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.data.collectionMonths").value(6))
				// 확장 시작 시각(collection_started_at)이 앵커다 — registered_at이 아니다(FE 폴링 30분 상한).
				.andExpect(jsonPath("$.data.collectionStartedAt").value("2026-08-12T19:00:00+09:00"))
				// createdAt은 registered_at 앵커라 확장 앵커와 다른 값이어야 한다(둘이 같아지면 폴링 상한이 깨진다).
				.andExpect(jsonPath("$.data.createdAt").value("2026-08-01T09:00:00+09:00"))
				.andExpect(jsonPath("$.data.collectionError").value(Matchers.nullValue()));
	}

	@Test
	void 응답의_collectionMonths는_자산이_아니라_링크_값이다() throws Exception {
		// 자산은 12(다른 유저의 max)지만 이 유저 신청은 3 — 응답은 유저 신청값.
		given(linkRepository.findActiveByUserAndBrand(7L, 100L))
				.willReturn(Optional.of(link(7L, 100L, "lizda_official", BrandAccountType.OWN, 3)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.collectionStatus").value("ready"))
				.andExpect(jsonPath("$.data.collectionMonths").value(3))
				// 다음 스윕 표기는 운영 브랜드 스윕 시각(KST 02:00) 고정 — 날짜부는 실행일에 따라 변하므로
				// 시각 접미사만 검증한다(08-12 정정: 기본값 sweep-hour-kst=2).
				.andExpect(jsonPath("$.data.nextScheduledAt").value(Matchers.endsWith("T02:00:00+09:00")));
	}

	@Test
	void 백필_오류는_error와_collectionError를_반환한다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(errorRow(100L)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.collectionStatus").value("error"))
				.andExpect(jsonPath("$.data.collectionError.code").value("BACKFILL_FAILED"))
				.andExpect(jsonPath("$.data.collectionError.message").value("초기 수집에 실패했어요. 자동으로 재시도 중이에요."));
	}

	@Test
	void 남의_브랜드_단건은_403이다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 999L)).willReturn(Optional.empty());

		mockMvc.perform(get("/v1/brand-monitoring/accounts/999").with(user(principal())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		then(brandReadRepository).should(never()).findAccount(anyLong());
	}

	@Test
	void 숫자가_아닌_계정_id는_404다() throws Exception {
		mockMvc.perform(get("/v1/brand-monitoring/accounts/brand_lizda").with(user(principal())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	// ---------- 타입 변경(PATCH) ----------

	@Test
	void PATCH는_재수집_없이_타입만_바꾼다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L))
				.willReturn(List.of(link(7L, 10L, "my_brand", BrandAccountType.OWN)));
		given(linkRepository.findActiveByUserAndBrand(7L, 10L))
				.willReturn(Optional.of(link(7L, 10L, "my_brand", BrandAccountType.COMPETITOR)));
		given(brandReadRepository.findAccount(10L)).willReturn(Optional.of(readyRow(10L)));

		mockMvc.perform(patch("/v1/brand-monitoring/accounts/10").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"accountType\":\"competitor\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.accountType").value("competitor"));

		then(linkRepository).should().updateAccountType(7L, 10L, "competitor");
		// 한도 판정이 유저 잠금 아래에서 이뤄져야 한다(BrandLinkTransaction javadoc) — 잠금이 빠지면
		// 동시 PATCH 둘이 같은 자리를 보고 competitor 상한을 영구히 넘긴다. 슬라이스라 잠금의 효과는
		// 관측 불가라 호출 사실을 고정한다.
		then(linkRepository).should().lockUser(7L);
	}

	@Test
	void PATCH_대상_타입이_차_있으면_409고_바꾸지_않는다() throws Exception {
		// POST 재등록의 타입 변경(precheck 분기)과는 다른 트랜잭션 메서드(changeType)라 별도로 고정한다 —
		// 둘이 공유하는 것은 상한 판정(requireRoom)뿐이고, 잠금·재조회 순서는 각자 구현이다.
		List<BrandLinkRow> existing = new ArrayList<>(links(3, BrandAccountType.COMPETITOR));
		existing.add(link(7L, 100L));   // own인 lizda_official — 이걸 competitor로 옮기려는 요청이다.
		given(linkRepository.findAllActiveByUser(7L)).willReturn(existing);

		mockMvc.perform(patch("/v1/brand-monitoring/accounts/100").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"accountType\":\"competitor\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("COMPETITOR_ACCOUNT_LIMIT_REACHED"));

		then(linkRepository).should(never()).updateAccountType(anyLong(), anyLong(), anyString());
	}

	@Test
	void PATCH_남의_계정은_403이다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of());

		mockMvc.perform(patch("/v1/brand-monitoring/accounts/999").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"accountType\":\"competitor\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void PATCH_값_공간_밖_타입은_400이다() throws Exception {
		mockMvc.perform(patch("/v1/brand-monitoring/accounts/10").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"accountType\":\"rival\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	// PATCH는 등록의 "생략 = own" 폴백을 쓰지 않는다(08-12 리뷰) — 보내지 않은 필드가 계정을 own으로
	// 덮어쓰면 경쟁사가 조용히 강등되고, own이 6개면 보내지도 않은 필드 때문에 409가 난다.
	// 아래 3개는 그 폴백이 되살아나면 즉시 깨진다.

	@Test
	void PATCH_본문이_아예_없으면_400이고_바꾸지_않는다() throws Exception {
		mockMvc.perform(patch("/v1/brand-monitoring/accounts/10").with(user(principal())).with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(linkRepository).should(never()).updateAccountType(anyLong(), anyLong(), anyString());
	}

	@Test
	void PATCH_accountType이_빠진_본문은_400이고_바꾸지_않는다() throws Exception {
		mockMvc.perform(patch("/v1/brand-monitoring/accounts/10").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(linkRepository).should(never()).updateAccountType(anyLong(), anyLong(), anyString());
	}

	@Test
	void PATCH_빈_문자열_타입은_400이고_바꾸지_않는다() throws Exception {
		mockMvc.perform(patch("/v1/brand-monitoring/accounts/10").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"accountType\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(linkRepository).should(never()).updateAccountType(anyLong(), anyLong(), anyString());
	}

	@Test
	void PATCH_숫자가_아닌_id는_타입도_틀렸어도_404다() throws Exception {
		// id 파싱(404)이 accountType 검증(400)보다 먼저다 — 둘 다 틀린 요청으로만 순서가 고정된다.
		mockMvc.perform(patch("/v1/brand-monitoring/accounts/abc").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"accountType\":\"rival\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	// ---------- 삭제 ----------

	@Test
	void 삭제는_마지막_사용자일_때만_monitoring_탈퇴를_호출한다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(linkRepository.softDeleteLink(7L, 100L)).willReturn(true);
		given(linkRepository.countActiveByBrand(100L)).willReturn(0);   // soft-delete 후 잔여 0
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(delete("/v1/brand-monitoring/accounts/100").with(user(principal())).with(csrf()))
				.andExpect(status().isNoContent());

		then(commandClient).should().deregisterBrand("lizda_official");
	}

	@Test
	void 삭제는_다른_활성_연결이_남으면_monitoring을_유지한다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(linkRepository.softDeleteLink(7L, 100L)).willReturn(true);
		given(linkRepository.countActiveByBrand(100L)).willReturn(1);

		mockMvc.perform(delete("/v1/brand-monitoring/accounts/100").with(user(principal())).with(csrf()))
				.andExpect(status().isNoContent());

		then(commandClient).should(never()).deregisterBrand(anyString());
	}

	@Test
	void 삭제의_monitoring_탈퇴가_실패해도_204다() throws Exception {
		// 연결 해제는 이미 커밋됐다 — 여기서 5xx를 내면 재시도가 403(이미 해제됨)이라 복구 불능이 된다.
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(linkRepository.softDeleteLink(7L, 100L)).willReturn(true);
		given(linkRepository.countActiveByBrand(100L)).willReturn(0);
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));
		willThrow(new MonitoringUnavailableException("연결 실패", null))
				.given(commandClient).deregisterBrand("lizda_official");

		mockMvc.perform(delete("/v1/brand-monitoring/accounts/100").with(user(principal())).with(csrf()))
				.andExpect(status().isNoContent());
	}

	@Test
	void 남의_브랜드_삭제는_403이고_해제하지_않는다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 999L)).willReturn(Optional.empty());

		mockMvc.perform(delete("/v1/brand-monitoring/accounts/999").with(user(principal())).with(csrf()))
				.andExpect(status().isForbidden());

		then(linkRepository).should(never()).softDeleteLink(anyLong(), anyLong());
		then(commandClient).should(never()).deregisterBrand(anyString());
	}

	// ---------- 해시태그 제외 문자열(스펙 2026-08-11 §2) ----------

	@Test
	void 제외_문자열_조회는_소유_브랜드만_허용한다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));
		given(commandClient.getHashtagExclusions("lizda_official")).willReturn(List.of("리즈다", "lizda"));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-exclusions").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.terms.length()").value(2))
				.andExpect(jsonPath("$.data.terms[0]").value("리즈다"))
				.andExpect(jsonPath("$.data.terms[1]").value("lizda"));

		then(commandClient).should().getHashtagExclusions("lizda_official");
	}

	@Test
	void 미소유_브랜드의_제외_문자열_조회는_거부된다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 999L)).willReturn(Optional.empty());

		mockMvc.perform(get("/v1/brand-monitoring/accounts/999/hashtag-exclusions").with(user(principal())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		then(commandClient).should(never()).getHashtagExclusions(anyString());
	}

	@Test
	void 제외_문자열_교체는_monitoring으로_위임한다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(put("/v1/brand-monitoring/accounts/100/hashtag-exclusions")
						.with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"terms\": [\"리즈다\", \"Lizda\"]}"))
				.andExpect(status().isNoContent());

		then(commandClient).should().putHashtagExclusions("lizda_official", List.of("리즈다", "Lizda"));
	}

	@Test
	void terms_null_교체는_빈_목록으로_위임한다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(put("/v1/brand-monitoring/accounts/100/hashtag-exclusions")
						.with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isNoContent());

		then(commandClient).should().putHashtagExclusions("lizda_official", List.of());
	}

	@Test
	void 미소유_브랜드의_제외_문자열_교체는_거부된다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 999L)).willReturn(Optional.empty());

		mockMvc.perform(put("/v1/brand-monitoring/accounts/999/hashtag-exclusions")
						.with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"terms\": [\"리즈다\"]}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		then(commandClient).should(never()).putHashtagExclusions(anyString(), any());
	}

	// ---------- 제외 문자열 단건 추가·삭제(2026-08-12, 표준 REST 확장) ----------

	@Test
	void 제외_문자열_추가는_monitoring으로_위임한다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts/100/hashtag-exclusions")
						.with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"terms\": [\"리즈다\"]}"))
				.andExpect(status().isNoContent());

		then(commandClient).should().addHashtagExclusions("lizda_official", List.of("리즈다"));
	}

	@Test
	void 미소유_브랜드의_제외_문자열_추가는_거부된다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 999L)).willReturn(Optional.empty());

		mockMvc.perform(post("/v1/brand-monitoring/accounts/999/hashtag-exclusions")
						.with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"terms\": [\"리즈다\"]}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		then(commandClient).should(never()).addHashtagExclusions(anyString(), any());
	}

	@Test
	void 제외_문자열_단건_삭제는_monitoring으로_위임한다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(delete("/v1/brand-monitoring/accounts/100/hashtag-exclusions/{term}", "리즈다")
						.with(user(principal())).with(csrf()))
				.andExpect(status().isNoContent());

		then(commandClient).should().deleteHashtagExclusion("lizda_official", "리즈다");
	}

	@Test
	void 미소유_브랜드의_제외_문자열_단건_삭제는_거부된다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 999L)).willReturn(Optional.empty());

		mockMvc.perform(delete("/v1/brand-monitoring/accounts/999/hashtag-exclusions/{term}", "리즈다")
						.with(user(principal())).with(csrf()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		then(commandClient).should(never()).deleteHashtagExclusion(anyString(), anyString());
	}

	@Test
	void 제외_문자열_전체_삭제는_monitoring으로_위임한다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(delete("/v1/brand-monitoring/accounts/100/hashtag-exclusions")
						.with(user(principal())).with(csrf()))
				.andExpect(status().isNoContent());

		then(commandClient).should().deleteAllHashtagExclusions("lizda_official");
	}

	@Test
	void 미소유_브랜드의_제외_문자열_전체_삭제는_거부된다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 999L)).willReturn(Optional.empty());

		mockMvc.perform(delete("/v1/brand-monitoring/accounts/999/hashtag-exclusions")
						.with(user(principal())).with(csrf()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		then(commandClient).should(never()).deleteAllHashtagExclusions(anyString());
	}

	@Test
	void monitoring_브랜드_비정합_404는_제외_문자열_조회에서_404로_매핑된다() throws Exception {
		// was 링크·brand_account는 정합이지만 monitoring이 그 브랜드를 모르는 비정합 경로 —
		// BrandController가 {code:"BRAND_NOT_FOUND", message} 에러 바디를 채워 주므로(08-11 정정,
		// 이전엔 빈 바디라 503으로 오승격됐다 — MonitoringBrandCommandClientTest 실측) exchange가
		// MonitoringApiException(404)으로 승격하고 V1ExceptionAdvice 공용 매핑이 그대로 404로 접는다.
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));
		given(commandClient.getHashtagExclusions("lizda_official"))
				.willThrow(new MonitoringApiException("BRAND_NOT_FOUND", "브랜드를 찾을 수 없습니다.", 404));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-exclusions").with(user(principal())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void monitoring_접속_불능은_제외_문자열_조회에서_503이다() throws Exception {
		// 에러 바디 유무와 무관한 진짜 전송 실패(타임아웃·연결 거부 등) 경로 — 다른 monitoring
		// 엔드포인트 4곳과 같은 503+Retry-After 계약이 유지되는지 확인.
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));
		given(commandClient.getHashtagExclusions("lizda_official"))
				.willThrow(new MonitoringUnavailableException("monitoring 접속 실패: read timeout", null));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-exclusions").with(user(principal())))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
				.andExpect(header().string("Retry-After", "5"));
	}

	@Test
	void 인증이_없으면_401이다() throws Exception {
		mockMvc.perform(get("/v1/brand-monitoring/accounts"))
				.andExpect(status().isUnauthorized());
	}

	// ---------- 태그 셋 관리(유저 입력, 2026-08-12) ----------

	@Test
	void 태그_조회는_소유_브랜드만_허용한다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));
		given(commandClient.getHashtagTags("lizda_official")).willReturn(List.of("리즈다", "lizda"));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-tags").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.tags.length()").value(2))
				.andExpect(jsonPath("$.data.tags[0]").value("리즈다"))
				.andExpect(jsonPath("$.data.tags[1]").value("lizda"));

		then(commandClient).should().getHashtagTags("lizda_official");
	}

	@Test
	void 미소유_브랜드의_태그_조회는_거부된다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 999L)).willReturn(Optional.empty());

		mockMvc.perform(get("/v1/brand-monitoring/accounts/999/hashtag-tags").with(user(principal())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		then(commandClient).should(never()).getHashtagTags(anyString());
	}

	@Test
	void 태그_교체는_monitoring으로_위임한다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(put("/v1/brand-monitoring/accounts/100/hashtag-tags")
						.with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\": [\"리즈다\", \"Lizda\"]}"))
				.andExpect(status().isNoContent());

		then(commandClient).should().putHashtagTags("lizda_official", List.of("리즈다", "Lizda"));
	}

	@Test
	void tags_null_교체는_빈_목록으로_위임한다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(put("/v1/brand-monitoring/accounts/100/hashtag-tags")
						.with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isNoContent());

		then(commandClient).should().putHashtagTags("lizda_official", List.of());
	}

	@Test
	void 미소유_브랜드의_태그_교체는_거부된다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 999L)).willReturn(Optional.empty());

		mockMvc.perform(put("/v1/brand-monitoring/accounts/999/hashtag-tags")
						.with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\": [\"리즈다\"]}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		then(commandClient).should(never()).putHashtagTags(anyString(), any());
	}

	// ---------- 태그 단건 추가·삭제(2026-08-12, 표준 REST 확장) ----------

	@Test
	void 태그_추가는_monitoring으로_위임한다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts/100/hashtag-tags")
						.with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\": [\"리즈다\"]}"))
				.andExpect(status().isNoContent());

		then(commandClient).should().addHashtagTags("lizda_official", List.of("리즈다"));
	}

	@Test
	void 미소유_브랜드의_태그_추가는_거부된다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 999L)).willReturn(Optional.empty());

		mockMvc.perform(post("/v1/brand-monitoring/accounts/999/hashtag-tags")
						.with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\": [\"리즈다\"]}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		then(commandClient).should(never()).addHashtagTags(anyString(), any());
	}

	/**
	 * POST 빈 목록·유효 문자 위반은 monitoring이 422(code VALIDATION)로 거부한다(계약 §8-3-1, PUT과
	 * 다른 규칙 — PUT은 2026-08-12부터 빈 목록을 허용한다) — addHashtagTags는 registerBrand의
	 * translate()를 거치지 않으므로 V1ExceptionAdvice 공용 매핑(httpStatus 404·5xx 외 4xx는 전부
	 * 400 VALIDATION_FAILED)이 그대로 적용된다.
	 */
	@Test
	void monitoring_422_태그_추가_거부는_400_VALIDATION_FAILED로_매핑된다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));
		willThrow(new MonitoringApiException("VALIDATION", "추가할 태그가 없습니다.", 422))
				.given(commandClient).addHashtagTags(anyString(), any());

		mockMvc.perform(post("/v1/brand-monitoring/accounts/100/hashtag-tags")
						.with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"tags\": []}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 태그_단건_삭제는_monitoring으로_위임한다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(delete("/v1/brand-monitoring/accounts/100/hashtag-tags/{tag}", "리즈다")
						.with(user(principal())).with(csrf()))
				.andExpect(status().isNoContent());

		then(commandClient).should().deleteHashtagTag("lizda_official", "리즈다");
	}

	@Test
	void 미소유_브랜드의_태그_단건_삭제는_거부된다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 999L)).willReturn(Optional.empty());

		mockMvc.perform(delete("/v1/brand-monitoring/accounts/999/hashtag-tags/{tag}", "리즈다")
						.with(user(principal())).with(csrf()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		then(commandClient).should(never()).deleteHashtagTag(anyString(), anyString());
	}

	@Test
	void 태그_전체_삭제는_monitoring으로_위임한다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(delete("/v1/brand-monitoring/accounts/100/hashtag-tags")
						.with(user(principal())).with(csrf()))
				.andExpect(status().isNoContent());

		then(commandClient).should().deleteAllHashtagTags("lizda_official");
	}

	@Test
	void 미소유_브랜드의_태그_전체_삭제는_거부된다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 999L)).willReturn(Optional.empty());

		mockMvc.perform(delete("/v1/brand-monitoring/accounts/999/hashtag-tags")
						.with(user(principal())).with(csrf()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		then(commandClient).should(never()).deleteAllHashtagTags(anyString());
	}

	@Test
	void monitoring_브랜드_비정합_404는_태그_조회에서_404로_매핑된다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));
		given(commandClient.getHashtagTags("lizda_official"))
				.willThrow(new MonitoringApiException("BRAND_NOT_FOUND", "브랜드를 찾을 수 없습니다.", 404));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-tags").with(user(principal())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void monitoring_접속_불능은_태그_조회에서_503이다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));
		given(commandClient.getHashtagTags("lizda_official"))
				.willThrow(new MonitoringUnavailableException("monitoring 접속 실패: read timeout", null));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-tags").with(user(principal())))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
				.andExpect(header().string("Retry-After", "5"));
	}
}
