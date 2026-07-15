package com.celfit.was.v1.events;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.account.RateLimiter;
import com.celfit.was.v1.common.V1ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * POST /v1/events/gate — 게이트/잠금 클릭 측정 이벤트(스펙 6.19). fire-and-forget 기록 전용이라
 * 프론트는 응답을 기다리지 않고 실패를 무시한다. 그래서 eventType이 없으면 4xx를 던지지 않고 조용히
 * 기록만 스킵한 뒤 204로 끝낸다(스펙 6.19 "4xx 남발 금지"). 레이트리밋 초과(429)만 허용되는 유일한 4xx다.
 *
 * 인증 Optional(SecurityConfig permitAll): principal이 있으면 user_id를 연결하고, 익명이면 null이다
 * (AppUserDetails 미일치 시 null — T5 V1ContentController와 동일 관례).
 */
@RestController
public class V1GateEventController {

	private final GateEventRepository repository;
	private final RateLimiter rateLimiter;
	private final ObjectMapper objectMapper;

	public V1GateEventController(GateEventRepository repository, RateLimiter rateLimiter,
			ObjectMapper objectMapper) {
		this.repository = repository;
		this.rateLimiter = rateLimiter;
		this.objectMapper = objectMapper;
	}

	@PostMapping("/v1/events/gate")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void gate(@AuthenticationPrincipal AppUserDetails principal,
			@RequestBody(required = false) GateEventRequest request, HttpServletRequest httpRequest) {
		// 레이트리밋은 스펙이 명시한 대상 — 초과 시에만 429(fire-and-forget이지만 허용되는 유일한 4xx). 키는 IP 단위.
		if (!rateLimiter.tryAcquire("gate:" + httpRequest.getRemoteAddr())) {
			throw V1ApiException.rateLimited();
		}
		// eventType이 없으면 프론트 UX에 영향 주지 않으려 4xx 대신 기록만 스킵하고 204로 끝낸다(스펙 6.19).
		if (request == null || request.eventType() == null || request.eventType().isBlank()) {
			return;
		}
		Long userId = principal == null ? null : principal.getUserId();
		// payload는 자유 형식 JSON 객체 — 문자열화 후 리포지토리가 jsonb로 캐스트 저장(null이면 SQL NULL).
		String payloadJson = request.payload() == null ? null : objectMapper.writeValueAsString(request.payload());
		repository.insert(userId, request.eventType(), payloadJson);
	}

	/** 요청 본문(스펙 6.19) — payload는 자유 형식 JSON 객체(그대로 jsonb 저장). */
	public record GateEventRequest(String eventType, JsonNode payload) {
	}
}
