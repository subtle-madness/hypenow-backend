package com.celfit.was.v1.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * v1 패키지 전용 에러 → envelope 매핑(스펙 3.2). 구 /api 표면에는 영향 없다(basePackages 한정).
 * 4xx는 클라이언트 잘못이라 로그를 남기지 않고, 캐치올 5xx만 error 로그를 남긴다(의도된 비대칭).
 */
@RestControllerAdvice(basePackages = "com.celfit.was.v1")
public class V1ExceptionAdvice {

	private static final Logger log = LoggerFactory.getLogger(V1ExceptionAdvice.class);

	@ExceptionHandler(V1ApiException.class)
	public ResponseEntity<ApiResponse<Void>> handle(V1ApiException e) {
		return ResponseEntity.status(e.status()).body(ApiResponse.fail(e.code(), e.getMessage()));
	}

	@ExceptionHandler({MethodArgumentTypeMismatchException.class,
			MissingServletRequestParameterException.class})
	public ResponseEntity<ApiResponse<Void>> handleBadParam(Exception e) {
		return ResponseEntity.badRequest()
				.body(ApiResponse.fail("VALIDATION_FAILED", "요청 값이 올바르지 않습니다."));
	}

	// 아래 3건은 캐치올이 프레임워크 4xx를 500으로 강등하지 않도록 명시 매핑한다.
	// 단, 405는 보통 핸들러 "선택 전" 매핑 단계에서 던져져 basePackages 한정 advice가 적용되지 않고
	// 기본 처리(405, 빈 본문)로 내려간다(실측) — 이 매핑은 핸들러 선택 후 발생하는 드문 경로용 방어다.
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
		return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
				.body(ApiResponse.fail("METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다."));
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
		return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
				.body(ApiResponse.fail("UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 형식이에요."));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
		return ResponseEntity.badRequest()
				.body(ApiResponse.fail("VALIDATION_FAILED", "요청 본문이 올바르지 않습니다."));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
		log.error("v1 처리 실패", e);
		return ResponseEntity.internalServerError()
				.body(ApiResponse.fail("INTERNAL_ERROR", "일시적인 오류가 발생했어요."));
	}
}
