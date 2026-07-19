package com.celfit.was.v1.account;

import com.celfit.was.v1.common.V1ApiException;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * v1 가입 요청 검증(스펙 6.15) — 위반 시 V1ApiException.validation(한국어 메시지).
 * enum Set 5종 + phoneCountryCode는 V3__users_profile_fields.sql의 CHECK 제약과 동일해야 한다.
 */
@Component
public class SignupValidator {

	private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
	private static final int PASSWORD_MIN_LENGTH = 8;
	private static final int USAGE_PURPOSE_MAX_LENGTH = 2000;

	private static final Set<String> USER_TYPES = Set.of("brand", "agency", "distributor", "influencer");
	private static final Set<String> SIGNUP_ROUTES = Set.of("portal_search", "blog_community", "pr_article",
			"social_media", "offline_event", "referral", "other");
	private static final Set<String> PHONE_COUNTRY_CODES = Set.of("+82", "+1", "+81", "+86");
	private static final Set<String> COMPANY_SIZES = Set.of("2-10", "11-50", "51-200", "201-500", "501-1000", "1001+");
	private static final Set<String> INDUSTRIES = Set.of("fashion", "beauty", "fnb", "home_living", "baby_kids");
	private static final Set<String> JOB_TITLES = Set.of("representative", "executive", "team_lead", "staff", "other");

	/** 가입 경량화(클로즈베타 2026-07-19) — 필수는 이메일·비밀번호·이름·userType·companyName·약관 3종뿐. */
	public void validate(SignupRequest request) {
		requireEmail(request.email());
		validatePassword(request.password());
		requireText(request.name(), "이름을 입력해 주세요.");
		requireIn(USER_TYPES, request.userType(), "userType");
		// 선택 필드 — 미입력(null)은 통과, 값이 있으면 어휘 검사(오타·임의값 저장 차단)
		optionalIn(SIGNUP_ROUTES, request.signupRoute(), "signupRoute");
		optionalIn(PHONE_COUNTRY_CODES, request.phoneCountryCode(), "phoneCountryCode");
		requireText(request.companyName(), "회사명을 입력해 주세요.");
		optionalIn(COMPANY_SIZES, request.companySize(), "companySize");
		optionalIn(INDUSTRIES, request.industry(), "industry");
		optionalIn(JOB_TITLES, request.jobTitle(), "jobTitle");
		if (request.usagePurpose() != null && request.usagePurpose().length() > USAGE_PURPOSE_MAX_LENGTH) {
			throw V1ApiException.validation("활용 목적은 %d자 이하로 입력해 주세요.".formatted(USAGE_PURPOSE_MAX_LENGTH));
		}
		if (!Boolean.TRUE.equals(request.agreedTerms()) || !Boolean.TRUE.equals(request.agreedPrivacy())
				|| !Boolean.TRUE.equals(request.agreedAge14())) {
			throw V1ApiException.validation("필수 약관에 모두 동의해 주세요.");
		}
	}

	/** 이메일 인증 발송(send) 재사용 — 가입과 동일한 형식 검사. */
	public void requireEmail(String email) {
		if (email == null || !EMAIL_PATTERN.matcher(email).matches()) {
			throw V1ApiException.validation("올바른 이메일 형식을 입력해 주세요.");
		}
	}

	/** PATCH /v1/me 재사용(스펙 6.13) — 가입과 동일한 어휘 검사. */
	public void requireJobTitle(String value) {
		requireIn(JOB_TITLES, value, "jobTitle");
	}

	/** PATCH /v1/me 재사용(스펙 6.13) — 가입과 동일한 어휘 검사. */
	public void requirePhoneCountryCode(String value) {
		requireIn(PHONE_COUNTRY_CODES, value, "phoneCountryCode");
	}

	/** 비밀번호 정책(스펙 6.15) — 8자 이상 + 영대문자·소문자·숫자·특수문자 각 1자 이상. PUT /v1/me/password도 재사용. */
	public void validatePassword(String password) {
		boolean ok = password != null
				&& password.length() >= PASSWORD_MIN_LENGTH
				&& password.chars().anyMatch(Character::isUpperCase)
				&& password.chars().anyMatch(Character::isLowerCase)
				&& password.chars().anyMatch(Character::isDigit)
				&& password.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
		if (!ok) {
			throw V1ApiException.validation("비밀번호는 8자 이상, 영문 대·소문자와 숫자, 특수문자를 모두 포함해야 해요.");
		}
	}

	private void requireText(String value, String message) {
		if (value == null || value.isBlank()) {
			throw V1ApiException.validation(message);
		}
	}

	private void requireIn(Set<String> allowed, String value, String field) {
		if (value == null || !allowed.contains(value)) {
			throw V1ApiException.validation(field + " 값이 올바르지 않아요.");
		}
	}

	/** 선택 필드 어휘 검사(경량화 2026-07-19) — 미입력(null)은 통과, 값이 있으면 requireIn과 동일. */
	private void optionalIn(Set<String> allowed, String value, String field) {
		if (value != null && !allowed.contains(value)) {
			throw V1ApiException.validation(field + " 값이 올바르지 않아요.");
		}
	}
}
