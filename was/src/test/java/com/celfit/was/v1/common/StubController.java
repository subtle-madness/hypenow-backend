package com.celfit.was.v1.common;

import com.celfit.was.monitoring.MonitoringApiException;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;

/**
 * V1ExceptionAdviceTest 전용 더미 컨트롤러 — advice(V1ExceptionAdvice)가 실제 v1 패키지 컨트롤러를
 * 잡는 방식을 검증하기 위한 픽스처. 중첩 static 클래스로 두면 컴포넌트 스캔 대상 빈으로 등록되지 않아
 * (@WebMvcTest(controllers=...)로 지정해도 라우팅 자체가 안 잡힘 — 실측 확인됨) 톱레벨 클래스로 둔다.
 * 패키지가 com.celfit.was.v1.common(= v1 하위)이라 advice의 basePackages="com.celfit.was.v1" 매칭 대상이다.
 */
@RestController
class StubController {

	@GetMapping("/v1/stub/not-found")
	ApiResponse<Void> notFound() {
		throw V1ApiException.notFound("콘텐츠를 찾을 수 없습니다.");
	}

	@GetMapping("/v1/stub/param")
	ApiResponse<String> param(@RequestParam int number) {
		return ApiResponse.ok("ok-" + number);
	}

	@GetMapping("/v1/stub/list")
	ApiResponse<String> list() {
		return ApiResponse.ok("rows", Map.of("total", 42));
	}

	@GetMapping("/v1/stub/boom")
	ApiResponse<Void> boom() {
		throw new RuntimeException("내부 사정");
	}

	// monitoring 에러 응답 매핑(V1ExceptionAdvice.handleMonitoringApi) 검증용 — code·httpStatus를
	// 쿼리 파라미터로 받아 그대로 실어 던진다. monitoring 원문 code·message가 응답에 새지 않는지도
	// 이 스텁으로 확인한다(일부러 눈에 띄는 값을 넣는다).
	@GetMapping("/v1/stub/monitoring-error")
	ApiResponse<Void> monitoringError(@RequestParam String code, @RequestParam int status) {
		throw new MonitoringApiException(code, "monitoring 내부 사정 — 절대 노출 금지", status);
	}

	// 클라 이탈(연결 끊김) 재현 — 실제로는 Jackson이 응답을 소켓에 스트리밍하다 write가 깨지면서
	// HttpMessageNotWritableException(원인: "Connection reset by peer" IOException)으로 감싸져 올라온다.
	// 소켓을 실제로 끊는 건 MockMvc로 재현 불가라 같은 형상의 예외를 직접 던져 advice의 분기만 고정한다.
	@GetMapping("/v1/stub/client-abort")
	ApiResponse<Void> clientAbort() {
		throw new HttpMessageNotWritableException("Could not write JSON",
				new IOException("Connection reset by peer"));
	}

	// 상류(monitoring) 연결이 끊긴 경우 — 메시지는 위와 똑같이 "Connection reset by peer"지만 이건
	// 우리 쪽 장애라 500이어야 한다. DisconnectedClientHelper의 제외 타입(RestClientException)이
	// 이 구분을 해준다 — 문자열만 봤다면 클라 이탈로 오인했을 자리다.
	@GetMapping("/v1/stub/upstream-abort")
	ApiResponse<Void> upstreamAbort() {
		throw new ResourceAccessException("I/O error on GET",
				new IOException("Connection reset by peer"));
	}

	// 핸들러 선택 후(인자 해석 단계) 415/본문 파싱 400이 나는 경로 — advice의 명시 매핑 검증용.
	@PostMapping("/v1/stub/echo")
	ApiResponse<Map<String, Object>> echo(@RequestBody Map<String, Object> body) {
		return ApiResponse.ok(body);
	}
}
