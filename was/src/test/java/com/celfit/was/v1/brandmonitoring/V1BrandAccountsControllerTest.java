package com.celfit.was.v1.brandmonitoring;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
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

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	private static BrandLinkRow link(long userId, long brandId) {
		return link(userId, brandId, "lizda_official");
	}

	private static BrandLinkRow link(long userId, long brandId, String username) {
		return new BrandLinkRow(brandId, userId, brandId, username, BrandAccountType.OWN,
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), null);
	}

	/** 한도 검증용 — 서로 다른 브랜드 n개에 연결된 상태(요청 계정명과 겹치지 않는 이름). */
	private static List<BrandLinkRow> links(int count) {
		List<BrandLinkRow> links = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			links.add(link(7L, 200L + i, "other_brand_" + i));
		}
		return links;
	}

	/** 첫 수집 진행 중 — 스윕 완주 사실(last_swept_at)이 아예 없다. backfill_error null. */
	private static BrandAccountRow collectingRow(long brandId, String username) {
		return new BrandAccountRow(brandId, username, null, null,
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), null, null,
				30876L, 12L, 340L, "브랜드 소개", "리즈다", "https://cdn/pic.jpg", true, "https://lizda.co.kr", "ACTIVE");
	}

	/** 백필 리셋·재가입·스윕 실패 — last_swept_on은 null이지만 지난 스윕 완주 사실이 있다. */
	private static BrandAccountRow sweptFactRow(long brandId, String backfillError) {
		return new BrandAccountRow(brandId, "lizda_official", null, OffsetDateTime.parse("2026-07-01T00:00:00Z"),
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), null, backfillError,
				30876L, 12L, 340L, null, null, "https://cdn/pic.jpg", null, null, "ACTIVE");
	}

	private static BrandAccountRow readyRow(long brandId) {
		return new BrandAccountRow(brandId, "lizda_official", LocalDate.of(2026, 8, 7),
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), OffsetDateTime.parse("2026-08-01T00:00:00Z"),
				OffsetDateTime.parse("2026-08-01T01:00:00Z"), null,
				30876L, 12L, 340L, null, null, "https://cdn/pic.jpg", null, null, "ACTIVE");
	}

	private static BrandAccountRow errorRow(long brandId) {
		return new BrandAccountRow(brandId, "lizda_official", null, null,
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), null, "초기 수집에 실패했어요. 자동으로 재시도 중이에요.",
				null, null, null, null, null, null, null, null, "ACTIVE");
	}

	// ---------- 연결 ----------

	@Test
	void 연결은_202와_collecting_계정을_반환한다() throws Exception {
		given(commandClient.registerBrand("lizda_official"))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));

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

		then(linkRepository).should().insertLink(7L, 100L, "lizda_official", BrandAccountType.OWN);
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
		given(commandClient.registerBrand("lizda_official"))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.id").value("100"))
				.andExpect(jsonPath("$.data.collectionStatus").value("ready"));

		then(linkRepository).should().insertLink(7L, 100L, "lizda_official", BrandAccountType.OWN);
	}

	@Test
	void 이미_연결된_계정_재요청은_멱등_202로_기존_객체를_돌려준다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.id").value("100"))
				.andExpect(jsonPath("$.data.collectionStatus").value("ready"));

		then(commandClient).should(never()).registerBrand(anyString());
		then(linkRepository).should(never()).insertLink(anyLong(), anyLong(), anyString(), anyString());
	}

	@Test
	void 다른_브랜드가_연결돼_있어도_추가_연결한다() throws Exception {
		// 구 계약의 BRAND_ACCOUNT_IMMUTABLE·ALREADY_EXISTS 지점 — 다계정 개정으로 정상 연결이다.
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link(7L, 200L, "other_brand")));
		given(commandClient.registerBrand("lizda_official"))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.id").value("100"));

		then(linkRepository).should().insertLink(7L, 100L, "lizda_official", BrandAccountType.OWN);
	}

	@Test
	void 한도_10개면_409_LIMIT_REACHED이고_monitoring을_호출하지_않는다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(links(10));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("BRAND_ACCOUNT_LIMIT_REACHED"));

		then(commandClient).should(never()).registerBrand(anyString());
	}

	@Test
	void 형식_위반은_400이고_monitoring을_호출하지_않는다() throws Exception {
		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"https://www.instagram.com/lizda_official/\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(commandClient).should(never()).registerBrand(anyString());
	}

	@Test
	void monitoring_404는_422_INSTAGRAM_ACCOUNT_NOT_FOUND로_번역된다() throws Exception {
		given(commandClient.registerBrand("lizda_official"))
				.willThrow(new MonitoringApiException("SUBJECT_NOT_FOUND", "인스타그램에서 계정·게시물을 찾을 수 없습니다.", 404));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.error.code").value("INSTAGRAM_ACCOUNT_NOT_FOUND"));
	}

	@Test
	void monitoring_422_비공개는_422_PRIVATE_ACCOUNT로_전달된다() throws Exception {
		given(commandClient.registerBrand("lizda_official"))
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
		given(commandClient.registerBrand("lizda_official"))
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
		given(commandClient.registerBrand("lizda_official"))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(linkRepository.insertLink(7L, 100L, "lizda_official", BrandAccountType.OWN))
				.willThrow(new DuplicateKeyException("brand_monitorings_active_user_brand_uidx"));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));

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
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(), links(10));
		given(commandClient.registerBrand("lizda_official"))
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
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(), links(10));
		given(commandClient.registerBrand("lizda_official"))
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
				.andExpect(jsonPath("$.meta.limit").value(10));
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
	void 단건은_ready_전이를_반영한다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.collectionStatus").value("ready"))
				.andExpect(jsonPath("$.data.collectionCompletedAt").value("2026-08-01T10:00:00+09:00"))
				.andExpect(jsonPath("$.data.lastDetectedAt").value("2026-08-07T09:00:00+09:00"))
				.andExpect(jsonPath("$.data.lastTrackedAt").value("2026-08-07T09:00:00+09:00"))
				.andExpect(jsonPath("$.data.nextScheduledAt").value(Matchers.endsWith("T03:00:00+09:00")))
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

	@Test
	void 인증이_없으면_401이다() throws Exception {
		mockMvc.perform(get("/v1/brand-monitoring/accounts"))
				.andExpect(status().isUnauthorized());
	}
}
