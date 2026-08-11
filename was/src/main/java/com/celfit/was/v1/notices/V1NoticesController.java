package com.celfit.was.v1.notices;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.notices.NoticeRepository;
import com.celfit.was.notices.NoticeSeenRepository;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.common.V1ApiException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 유저 표면 업데이트 소식 API — 발행분 목록(GET /v1/notices)과 마지막 확인 시각(GET·PUT
 * /v1/notices/seen). 인증은 SecurityConfig anyRequest().authenticated() 기본 잠금으로 충분해
 * 별도 보안 설정이 없다. 서비스 계층 없이 리포지토리를 직결한다(V1RegistrationsController 관례) —
 * 목록은 정렬·예약분 제외를 리포지토리가 이미 하고, seen은 upsert 하나뿐이라 여기서는 DTO 매핑·
 * 파싱만 한다.
 */
@RestController
public class V1NoticesController {

	private final NoticeRepository noticeRepository;
	private final NoticeSeenRepository seenRepository;

	public V1NoticesController(NoticeRepository noticeRepository, NoticeSeenRepository seenRepository) {
		this.noticeRepository = noticeRepository;
		this.seenRepository = seenRepository;
	}

	/** 예약 발행(미래 published_at)은 findPublished()가 이미 제외한다. meta.total = data.length(전량 반환). */
	@GetMapping("/v1/notices")
	public ApiResponse<List<NoticeResponse>> list() {
		List<NoticeResponse> items = noticeRepository.findPublished().stream()
				.map(NoticeResponse::from)
				.toList();
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", items.size());
		return ApiResponse.ok(items, meta);
	}

	@GetMapping("/v1/notices/seen")
	public ApiResponse<NoticeSeenResponse> seen(@AuthenticationPrincipal AppUserDetails principal) {
		OffsetDateTime lastSeenAt = seenRepository.findLastSeenAt(principal.getUserId()).orElse(null);
		return ApiResponse.ok(new NoticeSeenResponse(KstTimestamps.toKstIso(lastSeenAt)));
	}

	/** 받은 값을 파싱해 그대로 저장한다(역행 가드 없음). 파싱 실패·누락은 400 VALIDATION_FAILED. */
	@PutMapping("/v1/notices/seen")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void updateSeen(@AuthenticationPrincipal AppUserDetails principal, @RequestBody NoticeSeenRequest body) {
		if (body.lastSeenAt() == null) {
			throw V1ApiException.validation("마지막 확인 시각을 입력해 주세요.");
		}
		OffsetDateTime parsed;
		try {
			parsed = OffsetDateTime.parse(body.lastSeenAt());
		} catch (DateTimeParseException e) {
			throw V1ApiException.validation("마지막 확인 시각 형식이 올바르지 않습니다.");
		}
		seenRepository.upsert(principal.getUserId(), parsed);
	}
}
