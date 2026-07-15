package com.celfit.was.v1.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** v1 패키지 전용 에러 → envelope 매핑(스펙 3.2). 구 /api 표면에는 영향 없다(basePackages 한정). */
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

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
		log.error("v1 처리 실패", e);
		return ResponseEntity.internalServerError()
				.body(ApiResponse.fail("INTERNAL_ERROR", "일시적인 오류가 발생했어요."));
	}
}
