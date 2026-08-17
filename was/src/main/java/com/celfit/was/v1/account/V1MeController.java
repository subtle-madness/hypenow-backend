package com.celfit.was.v1.account;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.auth.UserProfile;
import com.celfit.was.auth.UserRepository;
import com.celfit.was.entitlement.EntitlementService;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * /v1/me 계정 관리(스펙 6.12~6.14) — 프로필 조회·부분 수정, 비밀번호 변경, 세션 목록/개별 로그아웃,
 * 프로필 이미지, 탈퇴. 전 경로 인증 필수(SecurityConfig `/v1/me/**` authenticated).
 * AppUserDetails는 안정 형상(userId·email)만 가지므로 프로필은 매 요청 UserRepository에서 읽는다.
 */
@RestController
public class V1MeController {

	private static final Logger log = LoggerFactory.getLogger(V1MeController.class);

	/** PATCH phoneNumber 형식 — 숫자·하이픈만, 1자 이상(스펙 6.13). */
	private static final Pattern PHONE_NUMBER_PATTERN = Pattern.compile("^[0-9-]+$");

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final SignupValidator signupValidator;
	private final SessionService sessionService;
	private final ProfileImageStore profileImageStore;
	private final AccountDeletionService accountDeletionService;
	private final EntitlementService entitlementService;

	public V1MeController(UserRepository userRepository, PasswordEncoder passwordEncoder,
			SignupValidator signupValidator, SessionService sessionService,
			ProfileImageStore profileImageStore, AccountDeletionService accountDeletionService,
			EntitlementService entitlementService) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.signupValidator = signupValidator;
		this.sessionService = sessionService;
		this.profileImageStore = profileImageStore;
		this.accountDeletionService = accountDeletionService;
		this.entitlementService = entitlementService;
	}

	record PasswordChangeRequest(String currentPassword, String newPassword) {
	}

	record DeleteAccountRequest(String password) {
	}

	record ProfileImageResponse(String profileImageUrl) {
	}

	@GetMapping("/v1/me")
	public ApiResponse<MeResponse> me(@AuthenticationPrincipal AppUserDetails principal) {
		return ApiResponse.ok(MeResponse.from(requireProfile(principal.getUserId())));
	}

	/** 조직·엔터프라이즈 entitlement 판정(설계 2026-08-17) — 매 요청 DB 기준 재계산, 무소속이면 plan:"free". */
	@GetMapping("/v1/me/entitlements")
	public ApiResponse<MeEntitlementsResponse> entitlements(@AuthenticationPrincipal AppUserDetails principal) {
		return ApiResponse.ok(MeEntitlementsResponse.from(entitlementService.entitlementsFor(principal.getUserId())));
	}

	/**
	 * 부분 업데이트(스펙 6.13) — "키 부재=유지 / null·빈 값=의미 있는 입력" 구분이 필요해
	 * Map으로 받아 containsKey로 판별한다(SavedController 관용구). 허용 외 키는 무시.
	 */
	@PatchMapping("/v1/me")
	public ApiResponse<MeResponse> patch(@AuthenticationPrincipal AppUserDetails principal,
			@RequestBody Map<String, Object> body) {
		Map<String, Object> columns = new LinkedHashMap<>();
		if (body.containsKey("name")) {
			columns.put("name", requireText(body.get("name"), "이름을 입력해 주세요."));
		}
		if (body.containsKey("nickname")) {
			// 빈 문자열은 "닉네임 제거" 의도 — null로 저장
			String nickname = stringValue(body.get("nickname"));
			columns.put("nickname", nickname == null || nickname.isBlank() ? null : nickname);
		}
		if (body.containsKey("jobTitle")) {
			String jobTitle = stringValue(body.get("jobTitle"));
			signupValidator.requireJobTitle(jobTitle);
			columns.put("job_title", jobTitle);
		}
		if (body.containsKey("phoneCountryCode")) {
			String code = stringValue(body.get("phoneCountryCode"));
			signupValidator.requirePhoneCountryCode(code);
			columns.put("phone_country_code", code);
		}
		if (body.containsKey("phoneNumber")) {
			String phoneNumber = stringValue(body.get("phoneNumber"));
			if (phoneNumber == null || !PHONE_NUMBER_PATTERN.matcher(phoneNumber).matches()) {
				throw V1ApiException.validation("전화번호는 숫자와 하이픈만 입력할 수 있어요.");
			}
			columns.put("phone_number", phoneNumber);
		}
		if (body.containsKey("companyName")) {
			columns.put("company_name", requireText(body.get("companyName"), "회사명을 입력해 주세요."));
		}
		if (body.containsKey("agreedMarketing")) {
			if (!(body.get("agreedMarketing") instanceof Boolean agreed)) {
				throw V1ApiException.validation("agreedMarketing 값이 올바르지 않아요.");
			}
			// marketing_updated_at 갱신(값이 실제로 바뀔 때만)은 patchProfile SQL이 처리한다
			columns.put("agreed_marketing", agreed);
		}
		if (columns.isEmpty()) {
			// 갱신할 허용 키가 없으면 현재 프로필 그대로 — 멱등 no-op
			return ApiResponse.ok(MeResponse.from(requireProfile(principal.getUserId())));
		}
		return ApiResponse.ok(MeResponse.from(userRepository.patchProfile(principal.getUserId(), columns)));
	}

	/**
	 * 성공 시 현재 세션은 유지, 나머지 세션 전부 무효화(탈취 세션 차단 — 스펙 6.13).
	 * DB(password_hash)가 정본 — 커밋 후 세션 정리 실패로 500을 내리면 클라이언트가
	 * "변경 실패"로 오해해 옛 비밀번호를 재시도하므로, 정리는 best-effort로 하고 204를 지킨다.
	 */
	@PutMapping("/v1/me/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void changePassword(@AuthenticationPrincipal AppUserDetails principal,
			@RequestBody PasswordChangeRequest request, HttpServletRequest httpRequest) {
		verifyPassword(principal.getUserId(), request.currentPassword());
		signupValidator.validatePassword(request.newPassword());
		userRepository.updatePasswordHash(principal.getUserId(), passwordEncoder.encode(request.newPassword()));
		try {
			sessionService.deleteOthers(principal.getUsername(), currentSessionId(httpRequest));
		} catch (RuntimeException e) {
			log.warn("비밀번호 변경은 완료, 커밋 후 타 세션 무효화 실패 — userId={}", principal.getUserId(), e);
		}
	}

	@GetMapping("/v1/me/sessions")
	public ApiResponse<List<SessionService.SessionView>> sessions(
			@AuthenticationPrincipal AppUserDetails principal, HttpServletRequest httpRequest) {
		return ApiResponse.ok(sessionService.listSessions(principal.getUsername(), currentSessionId(httpRequest)));
	}

	/**
	 * path의 sessionId는 alias(스펙 6.14) — 본인 목록에서 역매칭해 삭제한다. 매칭 없으면 404:
	 * 스펙의 타 유저 403은 alias 방식에선 "존재하는데 남의 것"과 "없음"을 구분할 수 없어 404로 은닉한다.
	 * 현재 세션을 지정하면 로그아웃과 동일 효과(다음 요청부터 미인증).
	 */
	@DeleteMapping("/v1/me/sessions/{sessionId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteSession(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String sessionId) {
		if (!sessionService.deleteByAlias(principal.getUsername(), sessionId)) {
			throw V1ApiException.notFound("세션을 찾을 수 없습니다.");
		}
	}

	@PutMapping("/v1/me/profile-image")
	public ApiResponse<ProfileImageResponse> uploadProfileImage(
			@AuthenticationPrincipal AppUserDetails principal,
			@RequestPart(name = "image", required = false) MultipartFile image) {
		if (image == null || image.isEmpty()) {
			throw V1ApiException.validation("이미지 파일을 첨부해 주세요.");
		}
		byte[] bytes;
		try {
			bytes = image.getBytes();
		} catch (IOException e) {
			throw new UncheckedIOException("업로드 이미지 읽기 실패", e);
		}
		String url = profileImageStore.store(principal.getUserId(), bytes);
		userRepository.updateProfileImageUrl(principal.getUserId(), url);
		return ApiResponse.ok(new ProfileImageResponse(url));
	}

	/** 멱등 — 이미지가 없어도 204(파일·컬럼 정리만). */
	@DeleteMapping("/v1/me/profile-image")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteProfileImage(@AuthenticationPrincipal AppUserDetails principal) {
		profileImageStore.delete(principal.getUserId());
		userRepository.updateProfileImageUrl(principal.getUserId(), null);
	}

	/**
	 * 탈퇴(스펙 6.13) — 비밀번호 본인 확인 후 모니터링 target 해지(프론트 계약 4절 31번, 갭 B-2) →
	 * DB 삭제는 한 트랜잭션(AccountDeletionService.deleteAccount), 커밋 후 전 세션 무효화·이미지
	 * 파일 정리(DB 밖 자원이라 트랜잭션 밖). 모니터링 해지는 fail-open — 실패해도 탈퇴는 진행한다
	 * (AccountDeletionService 참조).
	 * DB 삭제가 정본 — 커밋 후 정리가 실패해도 500을 내리면 "탈퇴 실패"로 오해되고, 잔존 세션이
	 * 다른 인증 엔드포인트에서 삭제된 userId로 FK 위반을 일으키므로 정리는 best-effort + 204를 지킨다.
	 */
	@DeleteMapping("/v1/me")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteAccount(@AuthenticationPrincipal AppUserDetails principal,
			@RequestBody DeleteAccountRequest request, HttpServletRequest httpRequest) {
		verifyPassword(principal.getUserId(), request.password());
		accountDeletionService.deleteAccount(principal.getUserId());
		try {
			sessionService.deleteAll(principal.getUsername());
		} catch (RuntimeException e) {
			log.warn("탈퇴 DB 삭제는 완료, 커밋 후 전 세션 무효화 실패 — userId={}", principal.getUserId(), e);
			// 세션 저장소 장애와 독립인 서블릿 로컬 invalidate로 최소한 현재 요청 세션은 끊는다
			// (잔존 세션 재사용 축소 — 남의 기기 세션은 만료 스윕에 맡긴다)
			invalidateCurrentSessionQuietly(httpRequest);
		}
		try {
			profileImageStore.delete(principal.getUserId());
		} catch (RuntimeException e) {
			log.warn("탈퇴 DB 삭제는 완료, 커밋 후 프로필 이미지 정리 실패 — userId={}", principal.getUserId(), e);
		}
	}

	private UserProfile requireProfile(long userId) {
		// 세션은 살아 있는데 행이 없으면(동시 탈퇴 등) 재로그인 유도가 맞다
		return userRepository.findProfileById(userId)
				.orElseThrow(() -> V1ApiException.unauthorized("UNAUTHORIZED", "로그인이 필요합니다."));
	}

	private void verifyPassword(long userId, String rawPassword) {
		AppUser user = userRepository.findById(userId)
				.orElseThrow(() -> V1ApiException.unauthorized("UNAUTHORIZED", "로그인이 필요합니다."));
		if (rawPassword == null || !passwordEncoder.matches(rawPassword, user.passwordHash())) {
			throw new V1ApiException(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_MISMATCH",
					"현재 비밀번호가 일치하지 않습니다.");
		}
	}

	private static String currentSessionId(HttpServletRequest httpRequest) {
		HttpSession session = httpRequest.getSession(false);
		return session == null ? null : session.getId();
	}

	/** 세션 저장소 우회 무효화(best-effort) — invalidate 자체가 던져도 204 계약을 깨지 않는다. */
	private static void invalidateCurrentSessionQuietly(HttpServletRequest httpRequest) {
		try {
			HttpSession session = httpRequest.getSession(false);
			if (session != null) {
				session.invalidate();
			}
		} catch (RuntimeException e) {
			log.warn("현재 세션 로컬 invalidate도 실패", e);
		}
	}

	private static String stringValue(Object value) {
		return value == null ? null : value.toString();
	}

	private static String requireText(Object value, String message) {
		String text = stringValue(value);
		if (text == null || text.isBlank()) {
			throw V1ApiException.validation(message);
		}
		return text;
	}
}
