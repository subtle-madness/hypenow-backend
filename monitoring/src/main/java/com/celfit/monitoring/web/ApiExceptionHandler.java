package com.celfit.monitoring.web;

import com.celfit.monitoring.hiker.HikerFetchException;
import com.celfit.monitoring.hiker.PrivateAccountException;
import com.celfit.monitoring.hiker.ShareLinkUnresolvedException;
import com.celfit.monitoring.hiker.SubjectNotFoundException;
import com.celfit.monitoring.service.InvalidStateException;
import com.celfit.monitoring.service.SweepAlreadyRunningException;
import com.celfit.monitoring.service.TargetNotFoundException;
import com.celfit.monitoring.service.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 예외 → 계약 §2 에러 어휘(`{code, message}`) 매핑.
 * 인증 에러는 없다 — 접근 통제는 네트워크 소속이라 연결 자체가 안 되면 배선 문제다.
 * Hiker에서 온 예외는 message를 그대로 흘리지 않는다(응답 본문이 통째로 실릴 수 있어서) — 상세는 로그로.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ValidationException.class)
	public ResponseEntity<ApiError> handleValidation(ValidationException e) {
		return body(HttpStatus.BAD_REQUEST, "VALIDATION", e.getMessage());
	}

	/** 본문 파싱 실패(깨진 JSON·알 수 없는 type 값 등)도 형식 위반이다. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
		log.warn("요청 본문 파싱 실패: {}", e.getMessage());
		return body(HttpStatus.BAD_REQUEST, "VALIDATION", "요청 본문을 읽을 수 없습니다.");
	}

	@ExceptionHandler(TargetNotFoundException.class)
	public ResponseEntity<ApiError> handleTargetNotFound(TargetNotFoundException e) {
		return body(HttpStatus.NOT_FOUND, "TARGET_NOT_FOUND", e.getMessage());
	}

	@ExceptionHandler(SubjectNotFoundException.class)
	public ResponseEntity<ApiError> handleSubjectNotFound(SubjectNotFoundException e) {
		log.info("대상 부재: {}", e.getMessage());
		return body(HttpStatus.NOT_FOUND, "SUBJECT_NOT_FOUND", "인스타그램에서 계정·게시물을 찾을 수 없습니다.");
	}

	@ExceptionHandler(PrivateAccountException.class)
	public ResponseEntity<ApiError> handlePrivate(PrivateAccountException e) {
		log.info("비공개 계정: {}", e.getMessage());
		// UNPROCESSABLE_CONTENT == 422 (Spring 7에서 UNPROCESSABLE_ENTITY가 이 이름으로 대체됨)
		return body(HttpStatus.UNPROCESSABLE_CONTENT, "PRIVATE_ACCOUNT", "비공개 계정이라 수집할 수 없습니다.");
	}

	/** share URL 형식 불량·해소 불가(Hiker 400 등) — 계약 §2-6 SHARE_LINK_UNRESOLVED. */
	@ExceptionHandler(ShareLinkUnresolvedException.class)
	public ResponseEntity<ApiError> handleShareLinkUnresolved(ShareLinkUnresolvedException e) {
		log.info("share 링크 해소 불가: {}", e.getMessage());
		return body(HttpStatus.UNPROCESSABLE_CONTENT, "SHARE_LINK_UNRESOLVED", "공유 링크를 확인해 주세요.");
	}

	@ExceptionHandler(InvalidStateException.class)
	public ResponseEntity<ApiError> handleInvalidState(InvalidStateException e) {
		return body(HttpStatus.CONFLICT, "INVALID_STATE", e.getMessage());
	}

	/** 스윕 수동 트리거가 이미 실행 중인 스윕과 겹쳤다 — SweepGuard 획득 실패. */
	@ExceptionHandler(SweepAlreadyRunningException.class)
	public ResponseEntity<ApiError> handleSweepAlreadyRunning(SweepAlreadyRunningException e) {
		log.info("스윕 중복 트리거 차단");
		return body(HttpStatus.CONFLICT, "SWEEP_ALREADY_RUNNING", "이미 스윕이 실행 중입니다. 완료 후 다시 시도해 주세요.");
	}

	/** Hiker 일시 오류 — was는 그대로 실패를 전달하고, 재시도는 같은 registrationKey로 사용자 몫(계약 §4). */
	@ExceptionHandler(HikerFetchException.class)
	public ResponseEntity<ApiError> handleFetchFailed(HikerFetchException e) {
		log.warn("Hiker 수집 실패", e);
		return body(HttpStatus.BAD_GATEWAY, "FETCH_FAILED", "수집에 실패했습니다. 잠시 후 다시 시도해 주세요.");
	}

	/** 캐치올 — 스택트레이스 HTML 대신 계약 셰이프로 내린다. 5xx는 was가 재시도해도 되는 실패다(계약 §4). */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleUnexpected(Exception e) {
		// 프레임워크가 이미 상태를 정한 예외(없는 경로 404·405·415 …)는 그 상태를 보존한다.
		// 500으로 강등하면 오배선(계약 밖 경로 호출)을 monitoring 장애로 오인하게 된다.
		// 인스턴스 검사로 거르는 이유: NoResourceFoundException은 ErrorResponseException을 상속하지 않고
		// 스프링 ErrorResponse 인터페이스만 구현해서(실측) 타입 기반 @ExceptionHandler로는 못 잡는다.
		if (e instanceof ErrorResponse framework) {
			HttpStatusCode statusCode = framework.getStatusCode();
			HttpStatus status = HttpStatus.resolve(statusCode.value());
			log.warn("계약 밖 요청 — {} {}", statusCode.value(), e.getMessage());
			// 여기 code는 §2 어휘가 아니다 — 계약 표면(§2의 명령 3종: 등록·연장·해지) 밖 요청에만 나간다.
			return ResponseEntity.status(statusCode).body(new ApiError(
					status == null ? "HTTP_" + statusCode.value() : status.name(),
					status == null ? "요청을 처리할 수 없습니다." : status.getReasonPhrase()));
		}
		log.error("명령 처리 실패", e);
		return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "일시적인 오류가 발생했습니다.");
	}

	private static ResponseEntity<ApiError> body(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(new ApiError(code, message));
	}
}
