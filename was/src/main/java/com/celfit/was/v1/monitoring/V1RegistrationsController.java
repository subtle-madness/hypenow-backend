package com.celfit.was.v1.monitoring;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.monitoring.RegistrationRepository;
import com.celfit.was.v1.common.ApiResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 등록 처리 내역 조회 /v1(스펙 6.28) — 인증 필수(SecurityConfig anyRequest().authenticated()가 처리).
 * 요청 시각 내림차순 최근 50건(상수, 리포지토리가 정렬·건수 제한을 담당)만 data로 돌려주고,
 * meta.total은 창과 무관한 전체 처리 내역 건수다(스펙 1.4의 두 예외 목록 중 하나 — 6.32 다이제스트와
 * 같은 방식으로 meta.total > data.length가 정상 케이스).
 *
 * <p>서비스 계층 없이 리포지토리를 직결한다 — 정렬·limit·건수 집계 모두 리포지토리가 이미 하고
 * 여기서는 응답 DTO로 순수 매핑만 하기 때문이다. 분기·검증 등 로직이 붙는 시점에 서비스로 승격한다.
 */
@RestController
public class V1RegistrationsController {

	private static final int RECENT_LIMIT = 50;

	private final RegistrationRepository repository;

	public V1RegistrationsController(RegistrationRepository repository) {
		this.repository = repository;
	}

	@GetMapping("/v1/monitoring/registrations")
	public ApiResponse<List<RegistrationResponse>> list(@AuthenticationPrincipal AppUserDetails principal) {
		long userId = principal.getUserId();
		List<RegistrationResponse> items = repository.findRecentByUser(userId, RECENT_LIMIT).stream()
				.map(RegistrationResponse::from)
				.toList();
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", repository.countByUser(userId));
		return ApiResponse.ok(items, meta);
	}
}
