package com.celfit.was.v1.common;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

	// 핸들러 선택 후(인자 해석 단계) 415/본문 파싱 400이 나는 경로 — advice의 명시 매핑 검증용.
	@PostMapping("/v1/stub/echo")
	ApiResponse<Map<String, Object>> echo(@RequestBody Map<String, Object> body) {
		return ApiResponse.ok(body);
	}
}
