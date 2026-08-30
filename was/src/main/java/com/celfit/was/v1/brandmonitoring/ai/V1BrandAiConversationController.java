package com.celfit.was.v1.brandmonitoring.ai;

import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브랜드 AI 챗 대화 표면(FE 변경요청서 2026-08-28 §8) - 목록·상세·삭제. 대화 <b>생성</b>은 여기 없다 -
 * 챗 엔드포인트({@link V1BrandAiChatController})가 질의 처리 중에 만든다(다음 단계 - 질의 엔드포인트
 * 개편에서 배선). 이 컨트롤러는 이미 만들어진 대화를 읽고 지우는 것만 담당한다.
 *
 * <p>모든 조회·삭제가 userId 스코프를 강제한다 - 남의 대화·삭제된 대화는 조회·삭제 구분 없이 똑같이
 * 404다(존재 여부 자체를 노출하지 않는다, 브랜드 모니터링 사용자 격리 관용구).
 */
@RestController
@RequestMapping("/v1/brand-monitoring/ai/conversations")
@ConditionalOnProperty(name = {"monitoring.enabled", "monitoring.brand.ai.enabled"}, havingValue = "true")
public class V1BrandAiConversationController {

	private static final int DEFAULT_LIMIT = 20;
	private static final int MAX_LIMIT = 50;

	private final AiConversationRepository conversationRepository;
	private final AiChatLogRepository logRepository;

	public V1BrandAiConversationController(AiConversationRepository conversationRepository,
			AiChatLogRepository logRepository) {
		this.conversationRepository = conversationRepository;
		this.logRepository = logRepository;
	}

	@GetMapping
	public ApiResponse<List<AiConversationSummary>> list(@AuthenticationPrincipal AppUserDetails principal,
			@RequestParam String accountId, @RequestParam(required = false) Integer limit) {
		requireLogin(principal);
		long brandId = parseId(accountId, "accountId가 올바르지 않습니다.");
		List<AiConversationSummary> summaries = conversationRepository
				.list(principal.getUserId(), brandId, normalizeLimit(limit)).stream()
				.map(row -> AiConversationSummary.from(row, brandId))
				.toList();
		return ApiResponse.ok(summaries);
	}

	@GetMapping("/{id}")
	public ApiResponse<AiConversationDetail> detail(@AuthenticationPrincipal AppUserDetails principal,
			@PathVariable String id) {
		requireLogin(principal);
		long conversationId = parseConversationId(id);
		AiConversationRepository.ConversationRow row = conversationRepository
				.findOwnedActive(conversationId, principal.getUserId())
				.orElseThrow(() -> V1ApiException.notFound("대화를 찾을 수 없습니다."));
		List<AiConversationMessage> messages = buildMessages(logRepository.findByConversation(conversationId));
		return ApiResponse.ok(AiConversationDetail.of(row, messages));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal AppUserDetails principal, @PathVariable String id) {
		requireLogin(principal);
		long conversationId = parseConversationId(id);
		int deleted = conversationRepository.softDelete(conversationId, principal.getUserId());
		if (deleted == 0) {
			throw V1ApiException.notFound("대화를 찾을 수 없습니다.");
		}
		return ResponseEntity.noContent().build();
	}

	/**
	 * 로그 행을 메시지로 펼친다(§8) - 답변이 없는 행은 user 메시지 1건뿐이다. followUps·refs는
	 * 대화 전체에서 <b>마지막</b> assistant 메시지에만 붙인다(N개 행을 다 훑은 뒤 마지막 한 자리만
	 * 되돌아가 갈아끼운다 - 매 행마다 판단할 수 없다, 아직 더 답변이 남았는지 이 시점엔 모른다).
	 */
	private static List<AiConversationMessage> buildMessages(
			List<AiChatLogRepository.ConversationMessageRow> rows) {
		List<AiConversationMessage> messages = new ArrayList<>();
		int lastAssistantIndex = -1;
		AiChatLogRepository.ConversationMessageRow lastAnsweredRow = null;
		for (AiChatLogRepository.ConversationMessageRow row : rows) {
			messages.add(AiConversationMessage.of(AiConversationMessage.ROLE_USER, row.question(),
					row.presetId(), row.createdAt()));
			if (row.answer() != null) {
				messages.add(AiConversationMessage.of(AiConversationMessage.ROLE_ASSISTANT, row.answer(),
						null, row.createdAt()));
				lastAssistantIndex = messages.size() - 1;
				lastAnsweredRow = row;
			}
		}
		if (lastAssistantIndex >= 0) {
			messages.set(lastAssistantIndex, messages.get(lastAssistantIndex)
					.withFollowUpsAndReferences(lastAnsweredRow.followUps(), lastAnsweredRow.refs()));
		}
		return messages;
	}

	private static void requireLogin(AppUserDetails principal) {
		if (principal == null) {
			throw V1ApiException.unauthorized("UNAUTHORIZED", "로그인이 필요해요.");
		}
	}

	private static int normalizeLimit(Integer limit) {
		if (limit == null) {
			return DEFAULT_LIMIT;
		}
		return Math.min(Math.max(limit, 1), MAX_LIMIT);
	}

	/** id는 문자열 경로·쿼리 파라미터라 숫자가 아니면 존재할 수 없는 id → 404(브랜드 계정 컨트롤러 관용구). */
	private static long parseConversationId(String raw) {
		return parseId(raw, null);
	}

	private static long parseId(String raw, String validationMessage) {
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			if (validationMessage != null) {
				throw V1ApiException.validation(validationMessage);
			}
			throw V1ApiException.notFound("대화를 찾을 수 없습니다.");
		}
	}
}
