package com.celfit.was.v1.brandmonitoring.ai;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브랜드 AI 챗 답변 피드백(👍👎) 저장 표면(2026-09-02) - {@code PUT/DELETE
 * /v1/brand-monitoring/ai/messages/{messageId}/feedback}. 👎는 골드셋 발굴 필터로 쓴다(스펙
 * 2026-09-01 §2·§7-3) - 저장 자체가 목적이라 여기엔 별도 분석·집계 로직이 없다.
 *
 * <p>messageId는 app.ai_chat_logs 행 id(질문+답변 1쌍) - {@code POST .../messages} 응답의
 * messageId·SSE done 이벤트와 같은 값이다({@link V1BrandAiMessagesController}, FE는 질의 응답에서
 * 받은 값을 그대로 쓰면 된다). 소유 검증은 항상 userId 스코프 - 남의 메시지·삭제된 대화 아래
 * 메시지는 존재 여부를 노출하지 않고 404로 수렴한다({@link V1BrandAiConversationController}와 동일
 * 원칙, 브랜드 모니터링 사용자 격리 관용구).
 *
 * <p>PUT은 멱등이다 - 같은 메시지에 다시 보내면 이전 피드백을 덮어쓴다. DELETE는 세 컬럼을 전부
 * null로 되돌린다("피드백 취소").
 */
@RestController
@RequestMapping("/v1/brand-monitoring/ai/messages")
@ConditionalOnProperty(name = {"monitoring.enabled", "monitoring.brand.ai.enabled"}, havingValue = "true")
public class V1BrandAiFeedbackController {

	private static final Set<String> VALID_VALUES = Set.of("up", "down");
	private static final int MAX_COMMENT_LENGTH = 500;

	private final AiChatFeedbackRepository feedbackRepository;

	public V1BrandAiFeedbackController(AiChatFeedbackRepository feedbackRepository) {
		this.feedbackRepository = feedbackRepository;
	}

	@PutMapping("/{messageId}/feedback")
	public ApiResponse<AiFeedbackResponse> put(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String messageId, @RequestBody AiFeedbackRequest request) {
		requireLogin(principal);
		long id = parseMessageId(messageId);
		String value = validateValue(request == null ? null : request.value());
		String comment = validateComment(request == null ? null : request.comment());
		AiChatFeedbackRepository.FeedbackRow row = feedbackRepository
				.upsert(id, principal.getUserId(), value, comment)
				.orElseThrow(() -> V1ApiException.notFound("메시지를 찾을 수 없습니다."));
		return ApiResponse.ok(AiFeedbackResponse.of(messageId, row));
	}

	@DeleteMapping("/{messageId}/feedback")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String messageId) {
		requireLogin(principal);
		long id = parseMessageId(messageId);
		int updated = feedbackRepository.clear(id, principal.getUserId());
		if (updated == 0) {
			throw V1ApiException.notFound("메시지를 찾을 수 없습니다.");
		}
		return ResponseEntity.noContent().build();
	}

	private static void requireLogin(AppUserDetails principal) {
		if (principal == null) {
			throw V1ApiException.unauthorized("UNAUTHORIZED", "로그인이 필요해요.");
		}
	}

	private static String validateValue(String value) {
		if (value == null || !VALID_VALUES.contains(value)) {
			throw V1ApiException.validation("value는 up 또는 down이어야 합니다.");
		}
		return value;
	}

	/** 공백뿐이거나 비어 있으면 null로 정규화해 저장한다(빈 문자열을 "코멘트 있음"으로 잘못 취급하지 않게). */
	private static String validateComment(String comment) {
		if (comment == null) {
			return null;
		}
		String trimmed = comment.strip();
		if (trimmed.isEmpty()) {
			return null;
		}
		if (trimmed.codePointCount(0, trimmed.length()) > MAX_COMMENT_LENGTH) {
			throw V1ApiException.validation("comment는 500자 이내여야 합니다.");
		}
		return trimmed;
	}

	/** messageId는 문자열 경로 파라미터라 숫자가 아니면 400(대화 컨트롤러의 id는 404지만, 여기는
	 * 태스크 계약상 명시적으로 400 - 형식 오류와 "존재하지 않음"을 구분해 알려준다). */
	private static long parseMessageId(String raw) {
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			throw V1ApiException.validation("messageId가 올바르지 않습니다.");
		}
	}
}
