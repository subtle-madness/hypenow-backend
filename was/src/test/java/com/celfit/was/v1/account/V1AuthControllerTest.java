package com.celfit.was.v1.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.UserProfile;
import com.celfit.was.auth.UserRepository;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
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
 * 가입 코드는 배치 1회용(SignupCodeRepository mock) — 원자 소진·트랜잭션은 SignupCodeIntegrationTest가 실 DB로 커버.
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
	SignupCodeRepository signupCodeRepository;

	@MockitoBean
	SignupService signupService;

	private Authentication authenticated(String email) {
		return UsernamePasswordAuthenticationToken.authenticated(email, null, List.of());
	}

	/** UserSummary가 쓰는 4필드만 의미 있는 프로필 픽스처 — 나머지는 T3 확장 필드 기본값. */
	private UserProfile profile() {
		return new UserProfile(7L, "user@example.com", "김우민", null, "brand",
				"portal_search", "+82", "010-1234-5678", "하이프나우", "2-10", "beauty", "staff",
				false, null, null, OffsetDateTime.parse("2026-06-01T00:00:00Z"));
	}

	/** 이메일 중복 확인(6.24)이 findByEmail로 존재만 확인하므로 필요한 필드만 채운 최소 픽스처. */
	private AppUser existingUser() {
		return new AppUser(7L, "dup@example.com", "{bcrypt}hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z"));
	}

	@Test
	void 가입_코드가_유효하지_않으면_403_INVALID_SIGNUP_CODE다() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(true);
		given(signupCodeRepository.isUsable("WRONG")).willReturn(false);

		mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_SIGNUP_BODY.replace("BETA2026", "WRONG")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("INVALID_SIGNUP_CODE"))
				.andExpect(jsonPath("$.error.message").value("존재하지 않거나 이미 사용된 코드입니다."));

		then(signupService).shouldHaveNoInteractions();
	}

	@Test
	void 가입_코드가_빈_값이어도_403이다_fail_closed() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(true);

		// isUsable 스텁 없음(기본 false) — 빈 코드가 어떤 경로로도 뚫리면 안 된다
		mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_SIGNUP_BODY.replace("BETA2026", "")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("INVALID_SIGNUP_CODE"));

		then(signupService).shouldHaveNoInteractions();
	}

	@Test
	void 가입은_201과_UserSummary_envelope를_내린다() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(true);
		given(signupCodeRepository.isUsable("BETA2026")).willReturn(true);
		given(signupService.register(any())).willReturn(profile());
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

	// 가입 경량화(2026-07-19) — 프론트 요청서의 최소 페이로드 예시 그대로 201
	@Test
	void 가입은_선택_필드가_전부_null이어도_201이다() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(true);
		given(signupCodeRepository.isUsable("THREADS-A7K2")).willReturn(true);
		given(signupService.register(any())).willReturn(profile());
		given(authenticationManager.authenticate(any())).willReturn(authenticated("user@example.com"));

		mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"signupCode":"THREADS-A7K2","email":"user@example.com","password":"Passw0rd!",
								 "name":"홍길동","userType":"brand","companyName":"OO코스메틱",
								 "signupRoute":null,"phoneCountryCode":null,"phoneNumber":null,
								 "companySize":null,"industry":null,"jobTitle":null,"usagePurpose":null,
								 "agreedTerms":true,"agreedPrivacy":true,"agreedAge14":true,"agreedMarketing":false}"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void 가입_검증_위반은_400_VALIDATION_FAILED다() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(true);
		given(signupCodeRepository.isUsable("BETA2026")).willReturn(true);

		mockMvc.perform(post("/v1/auth/signup").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(VALID_SIGNUP_BODY.replace("Passw0rd!", " ")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 가입_중복_이메일은_409_EMAIL_ALREADY_EXISTS다() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(true);
		given(signupCodeRepository.isUsable("BETA2026")).willReturn(true);
		given(signupService.register(any()))
				.willThrow(V1ApiException.conflict("EMAIL_ALREADY_EXISTS", "이미 가입된 이메일이에요. 로그인해 주세요."));

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
		then(signupService).shouldHaveNoInteractions();
	}

	@Test
	void 코드_사전검증_유효하면_200_valid_true다() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(true);
		given(signupCodeRepository.isUsable("THREADS-A7K2")).willReturn(true);

		mockMvc.perform(post("/v1/auth/signup-code/verify").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"code":"THREADS-A7K2"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.valid").value(true))
				.andExpect(jsonPath("$.error").value(nullValue()));
	}

	@Test
	void 코드_사전검증_무효하면_403_INVALID_SIGNUP_CODE다() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(true);
		given(signupCodeRepository.isUsable(anyString())).willReturn(false);

		mockMvc.perform(post("/v1/auth/signup-code/verify").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"code":"USED-CODE"}"""))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.error.code").value("INVALID_SIGNUP_CODE"))
				.andExpect(jsonPath("$.error.message").value("존재하지 않거나 이미 사용된 코드입니다."));
	}

	@Test
	void 코드_사전검증_레이트리밋_초과는_429다() throws Exception {
		given(rateLimiter.tryAcquire(anyString())).willReturn(false);

		mockMvc.perform(post("/v1/auth/signup-code/verify").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"code":"THREADS-A7K2"}"""))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

		// 무차별 대입 방지 키는 IP 단위 — DB 조회 전에 걸린다
		then(rateLimiter).should().tryAcquire("signup-code-verify:127.0.0.1");
		then(signupCodeRepository).shouldHaveNoInteractions();
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

	// 가입 전 이메일 중복 확인(스펙 6.24) — 위저드 2스텝 디바운스 호출
	@Test
	void 이메일_사용_가능() throws Exception {
		given(rateLimiter.tryAcquire(anyString(), anyInt())).willReturn(true);
		given(userRepository.findByEmail("new@example.com")).willReturn(Optional.empty());

		mockMvc.perform(post("/v1/auth/email-availability").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"new@example.com"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.available").value(true));
	}

	@Test
	void 이메일_이미_가입() throws Exception {
		given(rateLimiter.tryAcquire(anyString(), anyInt())).willReturn(true);
		given(userRepository.findByEmail("dup@example.com")).willReturn(Optional.of(existingUser()));

		mockMvc.perform(post("/v1/auth/email-availability").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"dup@example.com"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.available").value(false));
	}

	@Test
	void 이메일_형식_위반은_400() throws Exception {
		given(rateLimiter.tryAcquire(anyString(), anyInt())).willReturn(true);

		mockMvc.perform(post("/v1/auth/email-availability").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"not-an-email"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 레이트리밋_초과는_429() throws Exception {
		given(rateLimiter.tryAcquire(anyString(), anyInt())).willReturn(false);

		mockMvc.perform(post("/v1/auth/email-availability").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"new@example.com"}"""))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
	}
}
