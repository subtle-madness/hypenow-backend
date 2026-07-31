package com.celfit.was.v1.common;

import com.celfit.was.monitoring.MonitoringApiException;
import com.celfit.was.monitoring.MonitoringUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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
 * v1·v2 패키지 전용 에러 → envelope 매핑(스펙 3.2). 구 /api 표면에는 영향 없다(basePackages 한정).
 * v2(발굴 리포트 v2, 스펙 6.22·6.23)도 V1ApiException·ApiResponse를 그대로 재사용하므로 같은 advice가 잡는다
 * — basePackages는 컨트롤러 패키지 기준(예외 패키지 기준 아님)이라 com.celfit.was.v2도 나열해야 한다.
 * 4xx는 클라이언트 잘못이라 로그를 남기지 않고, 캐치올 5xx만 error 로그를 남긴다(의도된 비대칭).
 */
@RestControllerAdvice(basePackages = {"com.celfit.was.v1", "com.celfit.was.v2"})
public class V1ExceptionAdvice {

	private static final Logger log = LoggerFactory.getLogger(V1ExceptionAdvice.class);

	@ExceptionHandler(V1ApiException.class)
	public ResponseEntity<ApiResponse<Void>> handle(V1ApiException e) {
		var builder = ResponseEntity.status(e.status());
		if (e.retryAfterSeconds() != null) {
			builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
		}
		return builder.body(ApiResponse.fail(e.code(), e.getMessage()));
	}

	/**
	 * monitoring 전송 실패(연결 불가·타임아웃·해석 불가 응답) — 계약 1.3 SERVICE_UNAVAILABLE.
	 * 같은 요청을 잠시 후 재시도 가능하다는 신호로 Retry-After를 함께 보낸다(초 단위, 계약이 정확한
	 * 값을 못 박지 않아 5초로 고정 — 등록 POST 10s·연장·해지 5s의 타임아웃 권고와 같은 자리수).
	 */
	@ExceptionHandler(MonitoringUnavailableException.class)
	public ResponseEntity<ApiResponse<Void>> handleMonitoringUnavailable(MonitoringUnavailableException e) {
		log.warn("monitoring 연결 실패 — 503 처리: {}", e.getMessage());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.header(HttpHeaders.RETRY_AFTER, "5")
				.body(ApiResponse.fail("SERVICE_UNAVAILABLE", "일시적으로 연결이 어려워요. 잠시 후 다시 시도해 주세요."));
	}

	/**
	 * monitoring이 명시 에러 응답 {code, message}을 준 경우(계약 §2) — 그대로 흘리지 않고 프론트
	 * 어휘(스펙 1.3)로 변환한다. 분기는 code가 아니라 httpStatus 기준(신규 code가 늘어도 매핑이
	 * 안 깨지도록):
	 *  - httpStatus >= 500 → 503 SERVICE_UNAVAILABLE + Retry-After: 5. monitoring 장애를 was 자체
	 *    캐치올 500과 구분해야 프론트가 "연동 서비스 문제"와 "우리 쪽 문제"를 가를 수 있다.
	 *  - httpStatus == 404 → 404 NOT_FOUND.
	 *  - 나머지 4xx(400 VALIDATION·409 INVALID_STATE·422 PRIVATE_ACCOUNT 등) → 400 VALIDATION_FAILED.
	 *    프론트 어휘엔 422·502가 없고, 스펙 6.30이 취소 INVALID_STATE를 "409 대신 400 VALIDATION_FAILED"로
	 *    못박아 둬서 4xx는 404 외엔 전부 여기로 뭉갠다.
	 * message는 monitoring 원문(필드명·enum 노출)을 절대 흘리지 않고 위 세 고정 문구만 쓴다.
	 * httpStatus가 4xx·5xx 밖인 경우는 도달 불가하다 — MonitoringCommandClient는
	 * RestClientResponseException(= HTTP 응답을 실제로 받은 경우)에서만 이 예외를 만들어 status가
	 * 항상 유효 HTTP 상태 코드다. 별도 else 분기를 두지 않는다.
	 * 4xx 매핑분도 포함해 전부 log.warn — 아래 4xx 핸들러들과 달리(그쪽은 로그 없음) 여기는
	 * 크로스 서비스 이상 신호라 운영 관찰이 필요해서 의도적으로 로그를 남긴다.
	 */
	@ExceptionHandler(MonitoringApiException.class)
	public ResponseEntity<ApiResponse<Void>> handleMonitoringApi(MonitoringApiException e) {
		log.warn("monitoring 에러 응답 — code={}, httpStatus={}: {}", e.code(), e.httpStatus(), e.getMessage());
		if (e.httpStatus() >= 500) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
					.header(HttpHeaders.RETRY_AFTER, "5")
					.body(ApiResponse.fail("SERVICE_UNAVAILABLE", "일시적으로 연결이 어려워요. 잠시 후 다시 시도해 주세요."));
		}
		if (e.httpStatus() == 404) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(ApiResponse.fail("NOT_FOUND", "대상을 찾을 수 없습니다."));
		}
		return ResponseEntity.badRequest()
				.body(ApiResponse.fail("VALIDATION_FAILED", "올바른 형식이 아니에요."));
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
