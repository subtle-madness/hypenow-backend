package com.celfit.was.admin;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 어드민 쓰기 API 에러 렌더(설계 2026-07-20, 07-22 확장) — 어드민이 본문을 그대로 노출하므로 {"error":메시지}.
 * assignableTypes로 어드민 쓰기 컨트롤러(적재·발송 표시)에만 적용.
 */
@RestControllerAdvice(assignableTypes = {AdminSignupCodeController.class, AdminSignupController.class})
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
