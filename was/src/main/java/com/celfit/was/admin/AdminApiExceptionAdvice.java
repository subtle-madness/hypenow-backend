package com.celfit.was.admin;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 가입 코드 적재 컨트롤러 전용 에러 렌더(설계 2026-07-20) — 어드민이 본문을 그대로 노출하므로 {"error":메시지}.
 * assignableTypes로 AdminSignupCodeController에만 적용(read용 AdminSignupController엔 영향 없음).
 */
@RestControllerAdvice(assignableTypes = AdminSignupCodeController.class)
public class AdminApiExceptionAdvice {

	@ExceptionHandler(AdminApiException.class)
	public ResponseEntity<Map<String, String>> handle(AdminApiException e) {
		return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException e) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "요청 본문을 읽을 수 없습니다."));
	}
}
