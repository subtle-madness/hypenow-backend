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
 * /v1/brand-monitoring/accounts 계약(스펙 §5-1·§5-2·§5-3) — 등록 409/422/503 분기, 상태 유도
 * (collecting/ready/error), 소유권 403, 삭제의 monitoring 탈퇴 조건을 고정한다.
 * V1CampaignControllerTest 관용구: SecurityConfig·V1ExceptionAdvice Import, DB·monitoring 접점은 mock.
 * 서비스·트랜잭션·어셈블러는 실 빈으로 붙여 플로우 전체가 실제로 돌게 한다(슬라이스에는 트랜잭션
 * 매니저가 없어 @Transactional은 무력하지만, 호출 순서·409 판정 로직은 그대로 검증된다).
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
		return new BrandLinkRow(1L, userId, brandId, "lizda_official",
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), null);
	}

	/** 백필 진행 중 — last_swept_on null · backfill_error null. last_swept_at은 지난 가입 잔존값. */
	private static BrandAccountRow collectingRow(long brandId, String username) {
		return new BrandAccountRow(brandId, username, null, OffsetDateTime.parse("2026-07-01T00:00:00Z"),
				OffsetDateTime.parse("2026-08-07T00:00:00Z"), null, null,
				30876L, 12L, 340L, "브랜드 소개", "리즈다", "https://cdn/pic.jpg", true, "https://lizda.co.kr", "ACTIVE");
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

	// ---------- 등록 ----------

	@Test
	void 등록은_202와_collecting_계정을_반환한다() throws Exception {
		given(linkRepository.instagramAccountNameForUpdate(7L)).willReturn(null);
		given(linkRepository.findActiveByUser(7L)).willReturn(Optional.empty());
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

		then(linkRepository).should().saveInstagramAccountName(7L, "lizda_official");
		then(linkRepository).should().insertLink(7L, 100L, "lizda_official");
	}

	@Test
	void 등록은_collecting이면_lastDetectedAt과_lastTrackedAt을_null로_감춘다() throws Exception {
		// 재가입 시 monitoring last_swept_at에 지난 가입의 잔존값이 남는다(사실값 유지 결정) —
		// ready 전에는 노출하지 않는다.
		given(linkRepository.instagramAccountNameForUpdate(7L)).willReturn(null);
		given(commandClient.registerBrand("lizda_official"))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data", Matchers.hasKey("lastDetectedAt")))
				.andExpect(jsonPath("$.data.lastDetectedAt").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.data.lastTrackedAt").value(Matchers.nullValue()));
	}

	@Test
	void 같은_값_활성_연결이면_409_ALREADY_EXISTS다() throws Exception {
		given(linkRepository.instagramAccountNameForUpdate(7L)).willReturn("lizda_official");
		given(linkRepository.findActiveByUser(7L)).willReturn(Optional.of(link(7L, 100L)));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("BRAND_ACCOUNT_ALREADY_EXISTS"));

		then(commandClient).should(never()).registerBrand(anyString());
	}

	@Test
	void 다른_값이_저장돼_있으면_409_IMMUTABLE이고_monitoring을_호출하지_않는다() throws Exception {
		given(linkRepository.instagramAccountNameForUpdate(7L)).willReturn("other_brand");

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("BRAND_ACCOUNT_IMMUTABLE"));

		then(commandClient).should(never()).registerBrand(anyString());
		then(linkRepository).should(never()).saveInstagramAccountName(anyLong(), anyString());
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
		given(linkRepository.instagramAccountNameForUpdate(7L)).willReturn(null);
		given(commandClient.registerBrand("lizda_official"))
				.willThrow(new MonitoringApiException("SUBJECT_NOT_FOUND", "인스타그램에서 계정·게시물을 찾을 수 없습니다.", 404));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.error.code").value("INSTAGRAM_ACCOUNT_NOT_FOUND"));
	}

	@Test
	void monitoring_422_비공개는_422_PRIVATE_ACCOUNT로_전달된다() throws Exception {
		given(linkRepository.instagramAccountNameForUpdate(7L)).willReturn(null);
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
		given(linkRepository.instagramAccountNameForUpdate(7L)).willReturn(null);
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
	void 삭제_후_같은_계정_재등록은_계정명을_다시_저장하지_않고_202다() throws Exception {
		// 계정명은 불변이라 삭제해도 users에 남는다(§5-4) — stored=같은 값 + 활성 연결 없음이
		// "삭제 후 같은 username 재등록" 경로다. 이때만 저장을 건너뛰고 연결만 새로 만든다.
		given(linkRepository.instagramAccountNameForUpdate(7L)).willReturn("lizda_official");
		given(linkRepository.findActiveByUser(7L)).willReturn(Optional.empty());
		given(commandClient.registerBrand("lizda_official"))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.id").value("100"))
				.andExpect(jsonPath("$.data.collectionStatus").value("collecting"));

		then(linkRepository).should(never()).saveInstagramAccountName(anyLong(), anyString());
		then(linkRepository).should().insertLink(7L, 100L, "lizda_official");
	}

	@Test
	void 등록_트랜잭션이_깨지면_보상_탈퇴를_호출하고_409를_돌려준다() throws Exception {
		// 동시 등록 경합 — 사전 확인은 통과했지만 활성 유니크 인덱스가 최후에 막는 경로.
		given(linkRepository.instagramAccountNameForUpdate(7L)).willReturn(null);
		given(commandClient.registerBrand("lizda_official"))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(linkRepository.insertLink(7L, 100L, "lizda_official"))
				.willThrow(new DuplicateKeyException("brand_monitorings_active_user_uidx"));
		given(linkRepository.countActiveByBrand(100L)).willReturn(0);
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(collectingRow(100L, "lizda_official")));

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("BRAND_ACCOUNT_ALREADY_EXISTS"));

		then(commandClient).should().deregisterBrand("lizda_official");
	}

	@Test
	void 등록_보상은_다른_활성_연결이_남아있으면_호출하지_않는다() throws Exception {
		given(linkRepository.instagramAccountNameForUpdate(7L)).willReturn(null);
		given(commandClient.registerBrand("lizda_official"))
				.willReturn(new MonitoringCommandClient.BrandRegisterResult(100L, "lizda_official", 30876L, "ACTIVE"));
		given(linkRepository.insertLink(7L, 100L, "lizda_official"))
				.willThrow(new DuplicateKeyException("brand_monitorings_active_user_uidx"));
		given(linkRepository.countActiveByBrand(100L)).willReturn(1);

		mockMvc.perform(post("/v1/brand-monitoring/accounts").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content("{\"username\": \"lizda_official\"}"))
				.andExpect(status().isConflict());

		then(commandClient).should(never()).deregisterBrand(anyString());
	}

	// ---------- 목록·단건 ----------

	@Test
	void 목록은_계정_없으면_빈_배열과_total_0이다() throws Exception {
		given(linkRepository.findActiveByUser(7L)).willReturn(Optional.empty());

		mockMvc.perform(get("/v1/brand-monitoring/accounts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(0))
				.andExpect(jsonPath("$.meta.total").value(0))
				.andExpect(jsonPath("$.meta.limit").value(10));
	}

	@Test
	void 목록은_활성_연결의_계정을_돌려준다() throws Exception {
		given(linkRepository.findActiveByUser(7L)).willReturn(Optional.of(link(7L, 100L)));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].id").value("100"))
				.andExpect(jsonPath("$.data[0].collectionStatus").value("ready"))
				.andExpect(jsonPath("$.meta.total").value(1));
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
		given(linkRepository.softDeleteActiveLink(7L)).willReturn(true);
		given(linkRepository.countActiveByBrand(100L)).willReturn(0);   // soft-delete 후 잔여 0
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(readyRow(100L)));

		mockMvc.perform(delete("/v1/brand-monitoring/accounts/100").with(user(principal())).with(csrf()))
				.andExpect(status().isNoContent());

		then(commandClient).should().deregisterBrand("lizda_official");
	}

	@Test
	void 삭제는_다른_활성_연결이_남으면_monitoring을_유지한다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(linkRepository.softDeleteActiveLink(7L)).willReturn(true);
		given(linkRepository.countActiveByBrand(100L)).willReturn(1);

		mockMvc.perform(delete("/v1/brand-monitoring/accounts/100").with(user(principal())).with(csrf()))
				.andExpect(status().isNoContent());

		then(commandClient).should(never()).deregisterBrand(anyString());
	}

	@Test
	void 삭제의_monitoring_탈퇴가_실패해도_204다() throws Exception {
		// 연결 해제는 이미 커밋됐다 — 여기서 5xx를 내면 재시도가 403(이미 해제됨)이라 복구 불능이 된다.
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link(7L, 100L)));
		given(linkRepository.softDeleteActiveLink(7L)).willReturn(true);
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

		then(linkRepository).should(never()).softDeleteActiveLink(anyLong());
		then(commandClient).should(never()).deregisterBrand(anyString());
	}

	@Test
	void 인증이_없으면_401이다() throws Exception {
		mockMvc.perform(get("/v1/brand-monitoring/accounts"))
				.andExpect(status().isUnauthorized());
	}
}
