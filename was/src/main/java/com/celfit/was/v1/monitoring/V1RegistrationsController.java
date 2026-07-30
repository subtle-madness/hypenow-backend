package com.celfit.was.v1.monitoring;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.monitoring.RegistrationRepository;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 등록 처리 내역 조회·확인(읽음) 처리 /v1(스펙 6.28, 확인 처리는 프론트 요청) — 인증 필수
 * (SecurityConfig anyRequest().authenticated()가 처리). 요청 시각 내림차순 최근 50건(상수,
 * 리포지토리가 정렬·건수 제한을 담당)만 data로 돌려주고, meta.total은 창과 무관한 전체 처리 내역
 * 건수다(스펙 1.4의 두 예외 목록 중 하나 — 6.32 다이제스트와 같은 방식으로 meta.total > data.length가
 * 정상 케이스).
 *
 * <p>서비스 계층 없이 리포지토리를 직결한다 — 정렬·limit·건수 집계·확인 처리 멱등 모두 리포지토리가
 * 이미 하고 여기서는 응답 DTO 매핑과 ids/all 분기만 하기 때문이다. 분기·검증 등 로직이 더 붙는
 * 시점에 서비스로 승격한다.
 */
@RestController
public class V1RegistrationsController {

	private static final int RECENT_LIMIT = 50;
	/** body 없는 POST(RequestBody required=false → null)를 빈 요청과 동일하게 다루기 위한 정규화 상수. */
	private static final RegistrationReadRequest EMPTY_REQUEST = new RegistrationReadRequest(null, null);

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

	/**
	 * 확인(읽음) 처리 — 알림 읽음(6.32)과 동일한 멱등 계약: 존재하지 않는 id·타 유저 id·기확인 id는
	 * 리포지토리가 조용히 무시한다(404·403 없음). all=true는 응답 창(최근 50건)과 무관하게 유저의
	 * 미확인 전체를 처리하며, ids와 함께 와도 all이 우선한다. 둘 다 없으면(또는 all=false만 오면) 400.
	 */
	@PostMapping("/v1/monitoring/registrations/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void read(@AuthenticationPrincipal AppUserDetails principal,
			@RequestBody(required = false) RegistrationReadRequest body) {
		long userId = principal.getUserId();
		RegistrationReadRequest request = body == null ? EMPTY_REQUEST : body;

		if (Boolean.TRUE.equals(request.all())) {
			repository.markAllAcknowledged(userId);
			return;
		}
		if (request.ids() == null) {
			throw V1ApiException.validation("확인 처리할 등록 처리 내역을 지정해 주세요.");
		}
		List<Long> parsedIds = request.ids().stream()
				.map(V1RegistrationsController::tryParseId)
				.filter(Objects::nonNull)
				.toList();
		repository.markAcknowledged(userId, parsedIds);
	}

	/** id는 문자열이라 숫자 변환 실패 원소는 "존재하지 않는 id"로 취급해 조용히 무시한다(멱등 규칙). */
	private static Long tryParseId(String raw) {
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
