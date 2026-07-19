package com.celfit.was.v1.inquiry;

import com.celfit.was.v1.account.RateLimiter;
import com.celfit.was.v1.account.SignupValidator;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /v1/inquiries — 도입문의 접수(클로즈베타 설계 2026-07-19, Public).
 * 코드 없는 방문자가 소속·목적을 남기면 운영자가 확인 후 수동으로 코드를 발송한다.
 * 스팸 방지는 IP 분당 2회 레이트리밋 — 시간 단위 윈도우 없이 기존 RateLimiter 재사용(결정 2026-07-19).
 */
@RestController
public class V1InquiryController {

	private static final int MESSAGE_MAX_LENGTH = 4000;

	private final InquiryRepository repository;
	private final RateLimiter rateLimiter;
	private final SignupValidator signupValidator;

	public V1InquiryController(InquiryRepository repository, RateLimiter rateLimiter,
			SignupValidator signupValidator) {
		this.repository = repository;
		this.rateLimiter = rateLimiter;
		this.signupValidator = signupValidator;
	}

	@PostMapping("/v1/inquiries")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<InquiryCreateResponse> submit(@RequestBody InquiryRequest request,
			HttpServletRequest httpRequest) {
		if (!rateLimiter.tryAcquire("inquiry:" + httpRequest.getRemoteAddr(), 2)) {
			throw V1ApiException.rateLimited();
		}
		validate(request);
		UUID id = repository.insert(request);
		return ApiResponse.ok(new InquiryCreateResponse(id.toString()));
	}

	/** 전 필드 필수 — 유형·이메일 어휘/형식은 가입과 동일 규칙(SignupValidator 재사용). */
	private void validate(InquiryRequest request) {
		signupValidator.requireUserType(request.userType());
		requireText(request.name(), "이름을 입력해 주세요.");
		signupValidator.requireEmail(request.email());
		requireText(request.organization(), "소속명을 입력해 주세요.");
		requireText(request.message(), "문의 내용을 입력해 주세요.");
		if (request.message().length() > MESSAGE_MAX_LENGTH) {
			throw V1ApiException.validation("문의 내용은 %d자 이하로 입력해 주세요.".formatted(MESSAGE_MAX_LENGTH));
		}
	}

	private static void requireText(String value, String message) {
		if (value == null || value.isBlank()) {
			throw V1ApiException.validation(message);
		}
	}
}
