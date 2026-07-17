package com.celfit.was.v1.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.UserProfile;
import com.celfit.was.auth.UserRepository;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.setting.AppSettingRepository;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * /v1/auth 계약 슬라이스 검증 — 실 DB·실 AuthenticationManager 없이 v1 envelope·에러 코드에 집중.
 * 세션 쿠키(hypenow-session)·실 인증 경로는 T7 E2E(실 DB)가 커버한다.
 */
@WebMvcTest(controllers = V1AuthController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({SignupValidator.class, V1ExceptionAdvice.class, SecurityConfig.class})
class V1AuthControllerTest {

	private static final String VALID_SIGNUP_BODY = """
			{"signupCode":"BETA2026",
			 "email":"user@example.com","password":"Passw0rd!","name":"김우민","nickname":null,
			 "userType":"brand","signupRoute":"portal_search","phoneCountryCode":"+82",
			 "phoneNumber":"010-1234-5678","companyName":"하이프나우","companySize":"2-10",
			 "industry":"beauty","jobTitle":"staff",
			 "agreedTerms":true,"agreedPrivacy":true,"agreedAge14":true,"agreedMarketing":false}""";

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	UserRepository userRepository;

	@MockitoBean
	AuthenticationManager authenticationManager;

	@MockitoBean
	RateLimiter rateLimiter;

	@MockitoBean
	AppSettingRepository appSettingRepository;

	/** 정상 경로 공통 스텁 — 코드 일치. 코드 케이스별 테스트는 개별 재스텁. */
	private void stubSignupCode(String value) {
		given(appSettingRepository.findValue("signup.code")).willReturn(Optional.of(value));
	}

	private Authentication authenticated(String email) {
		return UsernamePasswordAuthenticationToken.authenticated(email, null, List.of());
	}

	/** UserSummary가 쓰는 4필드만 의미 있는 프로필 픽스처 — 나머지는 T3 확장 필드 기본값. */
	private UserProfile profile() {
		return new UserProfile(7L, "user@example.com", "김우민", null, "brand",
				"portal_search", "+82", "010-1234-5678", "하이프나우", "2-10", "beauty", "staff",
				false, null, null, OffsetDateTime.parse("2026-06-01T00:00:00Z"));
	}

	@Test
	void 가입_코드_불일치는_403_INVALID_SIGNUP_CODE다() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(true);
		stubSignupCode("BETA2026");

		mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_SIGNUP_BODY.replace("BETA2026", "WRONG")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("INVALID_SIGNUP_CODE"))
				.andExpect(jsonPath("$.error.message").value("가입 코드를 확인해 주세요."));

		then(userRepository).shouldHaveNoInteractions();
	}

	@Test
	void 가입_코드_미설정이면_전면_차단이다_fail_closed() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(true);
		given(appSettingRepository.findValue("signup.code")).willReturn(Optional.empty());

		mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(VALID_SIGNUP_BODY))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("INVALID_SIGNUP_CODE"));

		then(userRepository).shouldHaveNoInteractions();
	}

	@Test
	void 가입_코드가_빈_값이어도_전면_차단이다_fail_closed() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(true);
		stubSignupCode("");

		// 요청도 빈 코드 — 빈 값끼리 "일치"로 뚫리면 안 된다
		mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_SIGNUP_BODY.replace("BETA2026", "")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("INVALID_SIGNUP_CODE"));

		then(userRepository).shouldHaveNoInteractions();
	}

	@Test
	void 가입_코드는_앞뒤_공백을_무시하고_대조한다() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(true);
		stubSignupCode(" BETA2026 ");
		given(userRepository.insertProfile(any(), anyString())).willReturn(profile());
		given(authenticationManager.authenticate(any())).willReturn(authenticated("user@example.com"));

		mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_SIGNUP_BODY.replace("BETA2026", "BETA2026 ")))
				.andExpect(status().isCreated());
	}

	@Test
	void 가입은_201과_UserSummary_envelope를_내린다() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(true);
		stubSignupCode("BETA2026");
		given(userRepository.insertProfile(any(), anyString()))
				.willReturn(profile());
		given(authenticationManager.authenticate(any())).willReturn(authenticated("user@example.com"));

		mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(VALID_SIGNUP_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.id").value("7"))
				.andExpect(jsonPath("$.data.email").value("user@example.com"))
				.andExpect(jsonPath("$.data.name").value("김우민"))
				.andExpect(jsonPath("$.data.userType").value("brand"))
				.andExpect(jsonPath("$.error").value(nullValue()));
	}

	@Test
	void 가입_검증_위반은_400_VALIDATION_FAILED다() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(true);
		stubSignupCode("BETA2026");

		mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_SIGNUP_BODY.replace("Passw0rd!", "passw0rd!")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 가입_중복_이메일은_409_EMAIL_ALREADY_EXISTS다() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(true);
		stubSignupCode("BETA2026");
		given(userRepository.insertProfile(any(), anyString()))
				.willThrow(new DuplicateKeyException("users_email_key"));

		mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(VALID_SIGNUP_BODY))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_EXISTS"))
				.andExpect(jsonPath("$.error.message").value("이미 가입된 이메일이에요. 로그인해 주세요."));
	}

	@Test
	void 가입_레이트리밋_초과는_429_RATE_LIMITED다() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(false);

		mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON).content(VALID_SIGNUP_BODY))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
				.andExpect(jsonPath("$.error.message").value("요청이 너무 잦아요. 잠시 후 다시 시도해 주세요."));

		// 가입 키는 IP 단위(계정 없는 단계) — 검증·insert 전에 걸린다
		then(rateLimiter).should().tryAcquire("signup:127.0.0.1");
		then(userRepository).shouldHaveNoInteractions();
	}

	// A@x.com / a@x.com이 같은 버킷 — 대소문자 변형으로 계정 차원 제한을 우회하지 못한다
	@Test
	void 로그인_레이트리밋_키는_이메일을_lower_정규화한다() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(true);
		given(authenticationManager.authenticate(any())).willReturn(authenticated("user@example.com"));
		given(userRepository.findProfileByEmail(anyString()))
				.willReturn(Optional.of(profile()));

		mockMvc.perform(post("/v1/auth/login").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"USER@Example.com","password":"Passw0rd!"}"""))
				.andExpect(status().isOk());

		then(rateLimiter).should().tryAcquire("login:user@example.com|127.0.0.1");
	}

	@Test
	void 로그인_성공은_200_UserSummary와_UA_세션_attribute를_남긴다() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(true);
		given(authenticationManager.authenticate(any())).willReturn(authenticated("user@example.com"));
		given(userRepository.findProfileByEmail("user@example.com"))
				.willReturn(Optional.of(profile()));

		MvcResult result = mockMvc.perform(post("/v1/auth/login").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/126.0 Safari/537.36")
						.content("""
								{"email":"user@example.com","password":"Passw0rd!"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.id").value("7"))
				.andExpect(jsonPath("$.data.userType").value("brand"))
				.andReturn();

		// Task 1 규약 — 세션 목록 표기용 UA attribute(스펙 6.14)
		assertThat(result.getRequest().getSession(false).getAttribute("session.browser")).isEqualTo("Chrome");
		assertThat(result.getRequest().getSession(false).getAttribute("session.os")).isEqualTo("Mac OS X");
	}

	@Test
	void 로그인_인증_실패는_401_INVALID_CREDENTIALS_단일_응답이다() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(true);
		given(authenticationManager.authenticate(any()))
				.willThrow(new BadCredentialsException("자격 증명 실패"));

		mockMvc.perform(post("/v1/auth/login").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"nobody@example.com","password":"Wrong0rd!"}"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
				.andExpect(jsonPath("$.error.message").value("이메일 또는 비밀번호를 확인해 주세요."));
	}

	@Test
	void 로그인_레이트리밋_초과는_429_RATE_LIMITED다() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(false);

		mockMvc.perform(post("/v1/auth/login").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"user@example.com","password":"Passw0rd!"}"""))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
				.andExpect(jsonPath("$.error.message").value("요청이 너무 잦아요. 잠시 후 다시 시도해 주세요."));
	}

	@Test
	void 로그아웃은_204_본문_없음이다() throws Exception {
		mockMvc.perform(post("/v1/auth/logout").with(csrf()))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));
	}

	@Test
	void 미인증_보호_경로는_401_UNAUTHORIZED_envelope다() throws Exception {
		mockMvc.perform(get("/v1/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
				.andExpect(jsonPath("$.error.message").value("로그인이 필요합니다."));
	}

	// 구 /api 표면의 미인증 401은 기존대로 빈 본문 — V1AwareEntryPoint의 fallback 경로 회귀 방지
	@Test
	void 미인증_구_api_경로는_빈_401을_유지한다() throws Exception {
		mockMvc.perform(get("/api/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().string(""));
	}
}
