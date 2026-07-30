package com.celfit.was.v1.monitoring;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.monitoring.DigestRepository;
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
import tools.jackson.databind.ObjectMapper;

/**
 * 알림 다이제스트 조회·읽음 처리 /v1(스펙 6.32) — 인증 필수(SecurityConfig anyRequest().authenticated()가
 * 처리, "/v1/notifications"는 화이트리스트 밖이라 기본 잠금이 그대로 적용된다). 다이제스트
 * 생성(9시 크론)은 후속 태스크 범위 — 이 컨트롤러는 조회·읽음 처리만 다룬다.
 */
@RestController
public class V1NotificationsController {

	private static final int RECENT_LIMIT = 30;

	private final DigestRepository repository;
	private final ObjectMapper objectMapper;

	public V1NotificationsController(DigestRepository repository, ObjectMapper objectMapper) {
		this.repository = repository;
		this.objectMapper = objectMapper;
	}

	/** 최근 다이제스트 — date DESC 최대 30건(상수, 1.4의 두 예외 중 하나), meta.total은 창과 무관한 전체 건수. */
	@GetMapping("/v1/notifications")
	public ApiResponse<List<DigestResponse>> list(@AuthenticationPrincipal AppUserDetails principal) {
		long userId = principal.getUserId();
		List<DigestResponse> items = repository.findRecentByUser(userId, RECENT_LIMIT).stream()
				.map(row -> DigestResponse.from(row, objectMapper))
				.toList();
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", repository.countByUser(userId));
		return ApiResponse.ok(items, meta);
	}

	/**
	 * 읽음 처리 — 멱등이며 부분 실패 개념이 없다: 존재하지 않는 id·타 유저 id·기읽음 id는
	 * 리포지토리가 조용히 무시한다(404·403 없음). all=true는 응답 창(최근 30건)과 무관하게
	 * 유저의 안읽음 전체를 처리하며, ids와 함께 와도 all이 우선한다. 둘 다 없으면(또는
	 * all=false만 오면) 400.
	 */
	@PostMapping("/v1/notifications/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void read(@AuthenticationPrincipal AppUserDetails principal,
			@RequestBody(required = false) NotificationReadRequest body) {
		long userId = principal.getUserId();
		Boolean all = body == null ? null : body.all();
		if (Boolean.TRUE.equals(all)) {
			repository.markAllRead(userId);
			return;
		}

		List<String> ids = body == null ? null : body.ids();
		if (ids == null) {
			throw V1ApiException.validation("읽음 처리할 알림을 지정해 주세요.");
		}
		List<Long> parsedIds = ids.stream()
				.map(V1NotificationsController::tryParseId)
				.filter(Objects::nonNull)
				.toList();
		repository.markRead(userId, parsedIds);
	}

	/** id는 문자열이라 숫자 변환 실패 원소는 "존재하지 않는 id"로 취급해 조용히 무시한다(6.32 멱등 규칙). */
	private static Long tryParseId(String raw) {
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
