package com.celfit.was.v1.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.auth.UserProfile;
import com.celfit.was.auth.UserRepository;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * /v1/me 계약 슬라이스 검증(스펙 6.12~6.14) — 리포지토리·세션·이미지 저장소는 목,
 * PasswordEncoder는 SecurityConfig의 실 BCrypt(비밀번호 확인 분기 실측). 매직 바이트·파일 IO는
 * ProfileImageStoreTest, alias·세션 정렬은 SessionServiceTest가 커버한다.
 */
@WebMvcTest(controllers = V1MeController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({SignupValidator.class, V1ExceptionAdvice.class, SecurityConfig.class})
class V1MeControllerTest {

	private static final String PASSWORD = "Passw0rd!";
	private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode(PASSWORD);

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	UserRepository userRepository;

	@MockitoBean
	SessionService sessionService;

	@MockitoBean
	ProfileImageStore profileImageStore;

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", PASSWORD_HASH, "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	private static UserProfile profile() {
		return new UserProfile(7L, "user@example.com", "김우민", "우민", "brand",
				"portal_search", "+82", "010-1234-5678", "하이프나우", "2-10", "beauty", "staff",
				true, OffsetDateTime.parse("2026-07-01T00:00:00Z"), null,
				OffsetDateTime.parse("2026-06-01T00:00:00Z"));
	}

	private void givenAppUser() {
		given(userRepository.findById(7L)).willReturn(Optional.of(
				new AppUser(7L, "user@example.com", PASSWORD_HASH, "USER", OffsetDateTime.parse("2026-06-01T00:00:00Z"))));
	}

	// --- GET /v1/me (스펙 6.12) ---

	@Test
	void 내_정보는_users_행_전체를_envelope로_내린다() throws Exception {
		given(userRepository.findProfileById(7L)).willReturn(Optional.of(profile()));

		mockMvc.perform(get("/v1/me").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.id").value("7"))
				.andExpect(jsonPath("$.data.email").value("user@example.com"))
				.andExpect(jsonPath("$.data.name").value("김우민"))
				.andExpect(jsonPath("$.data.nickname").value("우민"))
				.andExpect(jsonPath("$.data.userType").value("brand"))
				.andExpect(jsonPath("$.data.jobTitle").value("staff"))
				.andExpect(jsonPath("$.data.phoneCountryCode").value("+82"))
				.andExpect(jsonPath("$.data.phoneNumber").value("010-1234-5678"))
				.andExpect(jsonPath("$.data.companyName").value("하이프나우"))
				.andExpect(jsonPath("$.data.companySize").value("2-10"))
				.andExpect(jsonPath("$.data.industry").value("beauty"))
				.andExpect(jsonPath("$.data.signupRoute").value("portal_search"))
				.andExpect(jsonPath("$.data.agreedMarketing").value(true))
				.andExpect(jsonPath("$.data.marketingUpdatedAt").value("2026-07-01T00:00:00Z"))
				.andExpect(jsonPath("$.data.profileImageUrl").value(nullValue()))
				.andExpect(jsonPath("$.data.createdAt").value("2026-06-01T00:00:00Z"));
	}

	// --- PATCH /v1/me (스펙 6.13) ---

	@Test
	@SuppressWarnings("unchecked")
	void 부분_업데이트는_보낸_키만_컬럼으로_바꾸고_빈_nickname은_null이_되며_허용_외_키는_무시한다() throws Exception {
		given(userRepository.patchProfile(anyLong(), any())).willReturn(profile());

		mockMvc.perform(patch("/v1/me").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"새이름","nickname":"","agreedMarketing":true,"email":"hack@example.com","id":"999"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.id").value("7"));

		ArgumentCaptor<Map<String, Object>> columns = ArgumentCaptor.forClass(Map.class);
		then(userRepository).should().patchProfile(eq(7L), columns.capture());
		assertThat(columns.getValue()).containsOnlyKeys("name", "nickname", "agreed_marketing");
		assertThat(columns.getValue().get("name")).isEqualTo("새이름");
		assertThat(columns.getValue().get("nickname")).isNull(); // "" → null 저장
		assertThat(columns.getValue().get("agreed_marketing")).isEqualTo(true);
	}

	@Test
	void 허용_키가_하나도_없으면_갱신_없이_현재_프로필을_돌려준다() throws Exception {
		given(userRepository.findProfileById(7L)).willReturn(Optional.of(profile()));

		mockMvc.perform(patch("/v1/me").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"other@example.com"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.email").value("user@example.com"));

		then(userRepository).should(never()).patchProfile(anyLong(), any());
	}

	@Test
	void 빈_name은_400_VALIDATION_FAILED다() throws Exception {
		mockMvc.perform(patch("/v1/me").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"  "}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 어휘_밖_jobTitle은_400_VALIDATION_FAILED다() throws Exception {
		mockMvc.perform(patch("/v1/me").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"jobTitle":"ceo"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 숫자_하이픈_밖_phoneNumber는_400_VALIDATION_FAILED다() throws Exception {
		mockMvc.perform(patch("/v1/me").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"phoneNumber":"010 1234 5678"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	// --- PUT /v1/me/password (스펙 6.13) ---

	@Test
	void 비밀번호_변경_성공은_204고_현재_세션_외_전부_무효화한다() throws Exception {
		givenAppUser();
		MockHttpSession session = new MockHttpSession();

		mockMvc.perform(put("/v1/me/password").with(user(principal())).with(csrf())
						.session(session)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"currentPassword":"%s","newPassword":"NewPassw0rd!"}""".formatted(PASSWORD)))
				.andExpect(status().isNoContent());

		then(userRepository).should().updatePasswordHash(eq(7L), anyString());
		then(sessionService).should().deleteOthers("user@example.com", session.getId());
	}

	@Test
	void 현재_비밀번호_불일치는_400_CURRENT_PASSWORD_MISMATCH다() throws Exception {
		givenAppUser();

		mockMvc.perform(put("/v1/me/password").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"currentPassword":"Wrong0rd!","newPassword":"NewPassw0rd!"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("CURRENT_PASSWORD_MISMATCH"))
				.andExpect(jsonPath("$.error.message").value("현재 비밀번호가 일치하지 않습니다."));

		then(userRepository).should(never()).updatePasswordHash(anyLong(), anyString());
	}

	// 복잡도 정책 제거(2026-07-29) — 백엔드는 빈 값만 차단
	@Test
	void 빈_새_비밀번호는_400_VALIDATION_FAILED고_세션도_유지된다() throws Exception {
		givenAppUser();

		mockMvc.perform(put("/v1/me/password").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"currentPassword":"%s","newPassword":" "}""".formatted(PASSWORD)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(userRepository).should(never()).updatePasswordHash(anyLong(), anyString());
		then(sessionService).should(never()).deleteOthers(anyString(), any());
	}

	// --- GET /v1/me/sessions · DELETE /v1/me/sessions/{id} (스펙 6.14) ---

	@Test
	void 세션_목록은_alias_id와_current_판정을_담아_내린다() throws Exception {
		given(sessionService.listSessions(eq("user@example.com"), any())).willReturn(List.of(
				new SessionService.SessionView("a1b2c3d4e5f60718", "Chrome", "Mac OS X",
						"2026-07-15T09:00:00Z", true),
				new SessionService.SessionView("0011223344556677", "Safari", "iOS",
						"2026-07-01T00:00:00Z", false)));

		mockMvc.perform(get("/v1/me/sessions").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[0].id").value("a1b2c3d4e5f60718"))
				.andExpect(jsonPath("$.data[0].browser").value("Chrome"))
				.andExpect(jsonPath("$.data[0].os").value("Mac OS X"))
				.andExpect(jsonPath("$.data[0].loginAt").value("2026-07-15T09:00:00Z"))
				.andExpect(jsonPath("$.data[0].current").value(true))
				.andExpect(jsonPath("$.data[1].current").value(false));
	}

	@Test
	void 세션_개별_삭제는_alias_매칭_시_204다() throws Exception {
		given(sessionService.deleteByAlias("user@example.com", "a1b2c3d4e5f60718")).willReturn(true);

		mockMvc.perform(delete("/v1/me/sessions/a1b2c3d4e5f60718").with(user(principal())).with(csrf()))
				.andExpect(status().isNoContent());
	}

	// 본인 목록 밖 alias는 존재 여부 자체를 은닉 — 404(스펙 403 대신, 컨트롤러 주석 참조)
	@Test
	void 세션_alias_미매칭은_404_NOT_FOUND다() throws Exception {
		given(sessionService.deleteByAlias(anyString(), anyString())).willReturn(false);

		mockMvc.perform(delete("/v1/me/sessions/ffffffffffffffff").with(user(principal())).with(csrf()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	// --- PUT·DELETE /v1/me/profile-image (스펙 6.13) ---

	@Test
	void 이미지_업로드_성공은_저장_URL을_컬럼에_반영하고_돌려준다() throws Exception {
		given(profileImageStore.store(eq(7L), any())).willReturn("/profile-images/user-7.png?v=1784116800");

		mockMvc.perform(multipart(HttpMethod.PUT, "/v1/me/profile-image")
						.file(new MockMultipartFile("image", "avatar.png", "image/png", new byte[] {1, 2, 3}))
						.with(user(principal())).with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.profileImageUrl").value("/profile-images/user-7.png?v=1784116800"));

		then(userRepository).should().updateProfileImageUrl(7L, "/profile-images/user-7.png?v=1784116800");
	}

	@Test
	void 일_MB_초과_이미지는_413_스펙_문구다() throws Exception {
		given(profileImageStore.store(anyLong(), any())).willThrow(new V1ApiException(
				HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE", "이미지는 1MB 이하만 업로드할 수 있어요."));

		mockMvc.perform(multipart(HttpMethod.PUT, "/v1/me/profile-image")
						.file(new MockMultipartFile("image", "big.png", "image/png", new byte[] {1}))
						.with(user(principal())).with(csrf()))
				.andExpect(status().is(413))
				.andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"))
				.andExpect(jsonPath("$.error.message").value("이미지는 1MB 이하만 업로드할 수 있어요."));

		then(userRepository).should(never()).updateProfileImageUrl(anyLong(), any());
	}

	@Test
	void 형식_밖_이미지는_415_스펙_문구다() throws Exception {
		given(profileImageStore.store(anyLong(), any())).willThrow(new V1ApiException(
				HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "PNG, JPEG 형식만 업로드할 수 있어요."));

		mockMvc.perform(multipart(HttpMethod.PUT, "/v1/me/profile-image")
						.file(new MockMultipartFile("image", "avatar.gif", "image/gif", new byte[] {1}))
						.with(user(principal())).with(csrf()))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"))
				.andExpect(jsonPath("$.error.message").value("PNG, JPEG 형식만 업로드할 수 있어요."));
	}

	@Test
	void 이미지_파트_누락은_400_VALIDATION_FAILED다() throws Exception {
		mockMvc.perform(multipart(HttpMethod.PUT, "/v1/me/profile-image")
						.with(user(principal())).with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 이미지_삭제는_파일과_컬럼을_비우고_멱등_204다() throws Exception {
		mockMvc.perform(delete("/v1/me/profile-image").with(user(principal())).with(csrf()))
				.andExpect(status().isNoContent());

		then(profileImageStore).should().delete(7L);
		then(userRepository).should().updateProfileImageUrl(7L, null);
	}

	// --- DELETE /v1/me 탈퇴 (스펙 6.13) ---

	@Test
	void 탈퇴는_DB_삭제_후_전_세션_무효화와_이미지_정리_순서로_204다() throws Exception {
		givenAppUser();

		mockMvc.perform(delete("/v1/me").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"password":"%s"}""".formatted(PASSWORD)))
				.andExpect(status().isNoContent());

		InOrder order = inOrder(userRepository, sessionService, profileImageStore);
		order.verify(userRepository).deleteAccount(7L);
		order.verify(sessionService).deleteAll("user@example.com");
		order.verify(profileImageStore).delete(7L);
	}

	// DB 삭제(정본)가 끝난 뒤의 정리 실패로 500을 내리면 "탈퇴 실패" 오해 + 잔존 세션 FK 위반 — best-effort 계약
	@Test
	void 탈퇴는_세션_무효화가_실패해도_204고_현재_세션은_로컬로_끊고_이미지_정리는_계속한다() throws Exception {
		givenAppUser();
		org.mockito.BDDMockito.willThrow(new IllegalStateException("세션 저장소 장애"))
				.given(sessionService).deleteAll(anyString());
		MockHttpSession session = new MockHttpSession();

		mockMvc.perform(delete("/v1/me").with(user(principal())).with(csrf())
						.session(session)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"password":"%s"}""".formatted(PASSWORD)))
				.andExpect(status().isNoContent());

		then(userRepository).should().deleteAccount(7L);
		then(profileImageStore).should().delete(7L); // 세션 실패와 무관하게 후속 정리 진행
		assertThat(session.isInvalid()).isTrue(); // 저장소 우회 서블릿 로컬 invalidate
	}

	@Test
	void 비밀번호_변경은_타_세션_무효화가_실패해도_204다() throws Exception {
		givenAppUser();
		org.mockito.BDDMockito.willThrow(new IllegalStateException("세션 저장소 장애"))
				.given(sessionService).deleteOthers(anyString(), any());

		mockMvc.perform(put("/v1/me/password").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"currentPassword":"%s","newPassword":"NewPassw0rd!"}""".formatted(PASSWORD)))
				.andExpect(status().isNoContent());

		then(userRepository).should().updatePasswordHash(eq(7L), anyString()); // 해시 변경(정본)은 완료
	}

	@Test
	void 탈퇴_비밀번호_불일치는_400_CURRENT_PASSWORD_MISMATCH고_아무것도_지우지_않는다() throws Exception {
		givenAppUser();

		mockMvc.perform(delete("/v1/me").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"password":"Wrong0rd!"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("CURRENT_PASSWORD_MISMATCH"));

		then(userRepository).should(never()).deleteAccount(anyLong());
		then(sessionService).should(never()).deleteAll(anyString());
	}
}
