package com.celfit.was.v1.brandmonitoring.ai;

import com.celfit.was.v1.brandmonitoring.BrandPostAssembler;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 에이전트 루프(설계 §3) - 시스템 프롬프트 + 대화 이력을 LLM에 보내고, 툴 호출이 오면 실행해
 * 되먹이기를 반복하다 텍스트 답변이 나오면 끝낸다. SSE·서버 세션은 스코프 밖이다(설계 §10).
 *
 * <p>정지 조건이 네 겹이다(C2/I6) - 툴 호출 {@value #MAX_TOOL_CALLS}회(설계 §7), 누적 프롬프트
 * 토큰 {@value #PROMPT_TOKEN_BUDGET}(댓글 본문 무절단×매 턴 전체 재전송의 O(k²) 폭발 방지, I6),
 * 벽시계 예산 {@value #TIME_BUDGET_MILLIS}ms(1 LLM 호출 최악 92초까지 걸릴 수 있어 안전망 12회를
 * 다 채우면 수십 분이 걸리는 것을 막는다) 중 하나라도 걸리면 다음 턴을 툴 호출 불가로 보내 답변을
 * 강제한다. <b>강제 답변 턴은 1회로 제한한다(C2 잔여, 2026-08-28 재리뷰)</b> - mode=NONE인데도 모델이
 * functionCall만 돌려주면(관측된 병리 사례) 즉시 루프를 끊고 그 시점 결과로 FALLBACK_ANSWER를
 * 돌려준다 - 재시도하며 MAX_LLM_CALLS까지 계속 돌면 호출 1회가 최악 92초라 스레드가 십수 분씩
 * 잔류한다. 이 경로는 OUTCOME_LLM_CALL_CAP으로 기록하고(안전망 도달과 같은 병리이므로 같은 outcome을
 * 쓴다) warn을 남긴다.
 *
 * <p>강제 답변 턴에서도 tools 선언 자체는 유지하고 {@code toolConfig} mode만 NONE으로 막는다(I8) -
 * tools를 통째로 빼면 이전 턴의 functionCall/functionResponse 파트가 남은 히스토리와 조합돼 Vertex
 * 400 위험이 있다.
 *
 * <p>안전 필터 차단·thinking이 maxOutputTokens를 잠식한 MAX_TOKENS로 후보가 비거나 텍스트 없이
 * 끝나면(I7) OUTCOME_BLOCKED로 기록하고 정중한 안내 답변을 돌려준다 - FALLBACK_ANSWER를
 * OUTCOME_OK로 오분류하지 않는다.
 *
 * <p>툴 실패는 같은 툴 기준 1회만 재시도 지시를 붙여 되먹인다(설계 §8) - 두 번째부터는
 * {@code retry: false}로 "이 정보 없이 답하라"고 못 박는다. 그러지 않으면 모델이 같은 실패를
 * 상한까지 반복한다.
 *
 * <p>{@code referencedShortCodes}는 이번 실행에서 툴이 건드린 shortCode 전체가 아니라 답변 텍스트에
 * 실제로 인용된 것만 남긴다(N7, 2026-08-28 - {@link #referencedIn}).
 */
public class BrandAiAgent {

	/** 턴당 툴 호출 상한(설계 §7). */
	static final int MAX_TOOL_CALLS = 8;
	/** LLM 호출 안전망(M2) - 강제 답변 턴 1회 제한(C2 잔여)이 정상적으로는 항상 먼저 걸리므로 여기
	 * 도달은 이제 이론상으로만 남은 최후 방어선이다. */
	static final int MAX_LLM_CALLS = 12;
	/** 누적 프롬프트 토큰 예산(I6) - 댓글 본문 무절단 × 매 턴 전체 재전송이면 O(k²)로 토큰이
	 * 터진다. 절단(BrandAiToolbox)과 별개로 루프 차원의 두 번째 방어선이다. */
	static final int PROMPT_TOKEN_BUDGET = 60_000;
	/** 벽시계 예산(C2, 55초) - 컨트롤러의 60초 응답 계약(설계 §5) 안에 여유를 두고 강제 답변으로
	 * 전환한다. Vertex 요청 타임아웃을 45초로 줄여도(BrandAiConfig) 재시도 1회를 더하면 최악
	 * 92초까지 걸릴 수 있어, 이 예산이 소진되면 그 한 번의 호출을 끝으로 더 부르지 않는다. */
	static final long TIME_BUDGET_MILLIS = 55_000L;
	/** 정상 종료를 뜻하는 finishReason - 이 값이 아니면서 텍스트·툴 호출이 모두 없으면 막힌 것으로
	 * 본다(I7, SAFETY·MAX_TOKENS·후보 부재 전부 포함 - Vertex가 추가할 수 있는 다른 비정상 사유도
	 * 같은 취급이 맞다). */
	private static final String FINISH_REASON_STOP = "STOP";
	/** 대화 이력에서 되살리지 못한 답변을 대신할 문구. */
	private static final String FALLBACK_ANSWER =
			"확인한 내용을 정리하지 못했어요. 질문을 조금 더 좁혀서 다시 물어봐 주세요.";
	/** 안전 필터·응답 길이 제한으로 막혀 답변 자체를 만들지 못했을 때 돌려줄 안내 문구(I7). */
	private static final String BLOCKED_ANSWER =
			"이 질문에는 안전 정책이나 응답 길이 제한 때문에 답변을 만들지 못했어요. 질문을 조금 다르게 바꿔서 다시 시도해 주세요.";
	/** references 상한(FE 변경요청서 §7) - 답변이 아무리 많은 shortCode를 인용해도 참조 목록은 10개까지만. */
	private static final int MAX_REFERENCES = 10;

	private static final Logger log = LoggerFactory.getLogger(BrandAiAgent.class);

	private final GeminiChatClient client;
	private final BrandAiToolbox toolbox;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public BrandAiAgent(GeminiChatClient client, BrandAiToolbox toolbox, ObjectMapper objectMapper, Clock clock) {
		this.client = client;
		this.toolbox = toolbox;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	/** 기존 2-인자 관용구 유지(호환) - scope 없음(무필터)·brandId 제한 없음·추가 프롬프트 없음과 동일하다. */
	public AgentOutcome run(long userId, List<AiChatMessage> messages) {
		return run(userId, messages, null, null, "");
	}

	/** 기존 4-인자 관용구 유지(호환, F1 이전 호출부·단발 테스트 전용) - brandId 제한 없음과 동일하다. */
	public AgentOutcome run(long userId, List<AiChatMessage> messages, AiScope scope, String extraSystemPrompt) {
		return run(userId, messages, null, scope, extraSystemPrompt);
	}

	/**
	 * @param sessionBrandId     대화가 스코프된 brandId(F1, 2026-08-30 리뷰) - 컨트롤러가 accountIds[0]을
	 *                           검증해 얻은 값을 그대로 넘긴다. null이면 무제한(위 두 호환 오버로드
	 *                           전용 경로). 툴 실행 세션에 실려 brandId를 인자로 받는 툴이 이 값과 다른
	 *                           brandId를 요청하면 소유 여부와 무관하게 failed 결과로 막는다
	 *                           ({@link BrandAiToolbox.ToolSession}·{@link BrandAiToolbox}). 로그용
	 *                           {@code brandId}(모델이 실제로 조회한 브랜드, {@link AgentOutcome#brandId})
	 *                           와는 별개다 - 이름이 같지 않게 구분한다.
	 * @param scope              FE 화면 필터(T3, 2026-08-30) - null이면 무필터. 툴 실행 세션에 실려
	 *                           게시물 계열 툴 전부에 강제된다({@link BrandAiToolbox.ToolSession}).
	 * @param extraSystemPrompt  시스템 프롬프트 뒤에 이어붙일 문구(scope 요약 1줄 + 프리셋 지시문,
	 *                           T3·T4) - 빈 문자열이면 기존 프롬프트와 동일하다.
	 */
	public AgentOutcome run(long userId, List<AiChatMessage> messages, Long sessionBrandId, AiScope scope,
			String extraSystemPrompt) {
		List<JsonNode> contents = new ArrayList<>();
		for (AiChatMessage message : messages) {
			contents.add(AiChatMessage.ROLE_ASSISTANT.equals(message.role())
					? client.modelContent(message.content())
					: client.userContent(message.content()));
		}

		List<AiChatLogEntry.ToolCallLog> toolCalls = new ArrayList<>();
		LinkedHashSet<String> shortCodes = new LinkedHashSet<>();
		Map<String, Integer> failuresByTool = new HashMap<>();
		// 요청 스코프 인덱스 캐시(N2) - 이 run() 호출 안에서만 재사용하고 절대 넘어 살지 않는다
		// (BrandAiToolbox는 싱글턴 빈이라 캐시를 그쪽 인스턴스 필드에 두면 유저 간에 섞인다).
		BrandAiToolbox.ToolSession toolSession = new BrandAiToolbox.ToolSession(scope, sessionBrandId);
		String basePrompt = BrandAiPrompt.SYSTEM + (extraSystemPrompt == null ? "" : extraSystemPrompt);
		// 모델이 실제로 조회한 brandId(로그용, AgentOutcome#brandId) - 위 sessionBrandId(대화 스코프
		// 강제용)와는 목적이 달라 별도 변수로 관리한다.
		Long brandId = null;
		int promptTokens = 0;
		int outputTokens = 0;
		long deadline = clock.millis() + TIME_BUDGET_MILLIS;

		for (int llmCall = 1; llmCall <= MAX_LLM_CALLS; llmCall++) {
			// 매 호출 전에 남은 예산을 확인한다(C2) - 부족하면 이번 호출을 마지막으로 삼아 답변을 강제한다.
			boolean toolCapped = toolCalls.size() >= MAX_TOOL_CALLS;
			boolean capped = toolCapped || promptTokens >= PROMPT_TOKEN_BUDGET || clock.millis() >= deadline;
			// 원인별 문구 분기(N3, 2026-08-28 재리뷰) - 툴 상한은 TOOL_CAP_NOTE, 벽시계·토큰 예산은
			// TIME_BUDGET_NOTE. 이전에는 원인과 무관하게 항상 TIME_BUDGET_NOTE만 나가 TOOL_CAP_NOTE가
			// 죽은 코드였다.
			String systemPrompt = !capped ? basePrompt
					: basePrompt + (toolCapped ? BrandAiPrompt.TOOL_CAP_NOTE : BrandAiPrompt.TIME_BUDGET_NOTE);
			LlmTurn turn = client.generate(systemPrompt, contents, BrandAiToolSpecs.ALL, capped);
			promptTokens += turn.promptTokens();
			outputTokens += turn.outputTokens();

			if (turn.toolCalls().isEmpty()) {
				if (turn.text().isBlank() && isBlocked(turn.finishReason())) {
					List<String> blockedReferenced = referencedIn(BLOCKED_ANSWER, shortCodes);
					return new AgentOutcome(BLOCKED_ANSWER, blockedReferenced,
							buildReferences(toolSession, blockedReferenced),
							List.copyOf(toolCalls), promptTokens, outputTokens, brandId,
							AiChatLogEntry.OUTCOME_BLOCKED, false);
				}
				boolean hasRealAnswer = !turn.text().isBlank();
				String answer = hasRealAnswer ? turn.text() : FALLBACK_ANSWER;
				List<String> referenced = referencedIn(answer, shortCodes);
				return new AgentOutcome(answer, referenced, buildReferences(toolSession, referenced),
						List.copyOf(toolCalls), promptTokens, outputTokens, brandId,
						capped ? AiChatLogEntry.OUTCOME_TOOL_CAP : AiChatLogEntry.OUTCOME_OK, hasRealAnswer);
			}

			// 강제 답변 턴을 1회로 제한한다(C2 잔여) - mode=NONE으로 보냈는데도 모델이 functionCall만
			// 돌려주면, 다음 턴도 capped 상태 그대로라 또 강제 답변 턴을 보내게 되고 이게 병리적으로
			// MAX_LLM_CALLS까지 반복될 수 있다(호출 1회 최악 92초 × 최대 11회 추가 = 스레드 십수 분
			// 잔류, 재리뷰 실측). capped 턴에서 텍스트 없이 툴 호출만 돌아오면 재시도하지 않고 그 자리에서
			// 끊는다 - 이미 MAX_LLM_CALLS 안전망과 같은 병리이므로 같은 outcome으로 기록한다(M2).
			if (capped) {
				log.warn("AI 에이전트 강제 답변 턴에서도 툴 호출만 반환 - userId={}, 툴 호출 {}회", userId, toolCalls.size());
				List<String> referenced = referencedIn(FALLBACK_ANSWER, shortCodes);
				return new AgentOutcome(FALLBACK_ANSWER, referenced, buildReferences(toolSession, referenced),
						List.copyOf(toolCalls), promptTokens, outputTokens, brandId,
						AiChatLogEntry.OUTCOME_LLM_CALL_CAP, false);
			}

			contents.add(client.modelToolCallContent(turn.toolCalls()));
			List<GeminiChatClient.ToolResponse> responses = new ArrayList<>();
			for (LlmTurn.ToolCall call : turn.toolCalls()) {
				if (toolCalls.size() >= MAX_TOOL_CALLS) {
					responses.add(new GeminiChatClient.ToolResponse(call.name(),
							objectMapper.createObjectNode().put("error", "조회 가능 횟수를 모두 썼습니다.")
									.put("retry", false).toString()));
					continue;
				}
				AiToolResult result = toolbox.execute(toolSession, userId, call.name(), call.args());
				toolCalls.add(new AiChatLogEntry.ToolCallLog(call.name(), call.args(), result.rowCount()));
				shortCodes.addAll(result.shortCodes());
				// 소유 검증 실패 등 failed 결과의 brandId는 신뢰할 수 없다(M1) - 성공한 호출에서만 딴다.
				if (brandId == null && !result.failed() && call.args().hasNonNull("brandId")) {
					brandId = call.args().path("brandId").asLong();
				}
				responses.add(new GeminiChatClient.ToolResponse(call.name(),
						result.failed() ? withRetryHint(call.name(), result, failuresByTool)
								: result.payloadJson()));
			}
			contents.add(client.toolResultContent(responses));
		}

		log.warn("AI 에이전트 LLM 호출 안전망 도달 - userId={}, 툴 호출 {}회", userId, toolCalls.size());
		List<String> referenced = referencedIn(FALLBACK_ANSWER, shortCodes);
		return new AgentOutcome(FALLBACK_ANSWER, referenced, buildReferences(toolSession, referenced),
				List.copyOf(toolCalls), promptTokens, outputTokens, brandId, AiChatLogEntry.OUTCOME_LLM_CALL_CAP,
				false);
	}

	/**
	 * 참조 shortCode 필터(N7, 2026-08-28) - 툴이 이번 실행에서 건드린 shortCode 전체({@code
	 * shortCodes})가 아니라 <b>답변 텍스트에 실제로 인용된</b> 것만 referencedShortCodes로 내보낸다.
	 * 이전에는 list_posts 등이 훑고 지나간 코드가 답변에 한마디도 안 나와도 전부 참조 목록에 실렸다 -
	 * 프론트가 "이 답변이 가리키는 게시물"로 보여주는 배지치고는 과다 노출이다. shortCode는 영숫자·
	 * -·_ 조합이라 부분 문자열 오탐 위험이 낮아 코드 단위 contains로 충분하다(설계 판단). 답변에
	 * 코드가 하나도 없으면(FALLBACK_ANSWER·BLOCKED_ANSWER 등) 자연히 빈 배열이 된다.
	 */
	private static List<String> referencedIn(String answer, Set<String> touchedShortCodes) {
		List<String> referenced = new ArrayList<>();
		for (String code : touchedShortCodes) {
			if (answer.contains(code)) {
				referenced.add(code);
			}
		}
		return referenced;
	}

	/**
	 * references 조립(FE 변경요청서 §7, 2026-08-30) - referencedShortCodes를 라벨이 붙은 참조로
	 * 바꾼다. 라벨은 이번 실행에서 {@link BrandAiToolbox.ToolSession}에 캐시된 인덱스에서 찾는다
	 * (best-effort - list_hashtag_posts 산지처럼 인덱스를 거치지 않은 shortCode는 최소 라벨로
	 * 폴백한다). 최대 {@value #MAX_REFERENCES}개까지만 싣는다.
	 */
	private static List<ReferenceInfo> buildReferences(BrandAiToolbox.ToolSession toolSession,
			List<String> shortCodes) {
		List<ReferenceInfo> out = new ArrayList<>();
		for (String code : shortCodes) {
			if (out.size() >= MAX_REFERENCES) {
				break;
			}
			Optional<BrandPostAssembler.PostRef> ref = toolSession.findCachedRef(code);
			out.add(new ReferenceInfo(code, ref.map(BrandAiAgent::labelFor).orElse("게시물 " + code)));
		}
		return out;
	}

	/** views null이면 "@username"만(설계 §요구) - 캐시에서 조회수를 못 얻은 흔한 경우(withViews=false 인덱스)다. */
	private static String labelFor(BrandPostAssembler.PostRef ref) {
		if (ref.authorUsername() == null) {
			return ref.latestViews() == null ? "게시물 " + ref.shortcode()
					: String.format(Locale.KOREA, "%,d회", ref.latestViews());
		}
		String base = "@" + ref.authorUsername();
		return ref.latestViews() == null ? base : base + " · " + String.format(Locale.KOREA, "%,d회", ref.latestViews());
	}

	/** STOP이 아니면서 텍스트도 툴 호출도 없는 경우만 "막혔다"로 본다(I7) - finishReason 자체가
	 * 없는(구버전 응답 등) 경우는 정상 완료로 보고 기존 FALLBACK_ANSWER 경로를 그대로 탄다. */
	private static boolean isBlocked(String finishReason) {
		return finishReason != null && !FINISH_REASON_STOP.equals(finishReason);
	}

	/** 같은 툴의 첫 실패에만 retry=true를 붙인다 - 두 번째부터는 물러나라고 지시한다(설계 §8). */
	private String withRetryHint(String toolName, AiToolResult result, Map<String, Integer> failuresByTool) {
		int failures = failuresByTool.merge(toolName, 1, Integer::sum);
		ObjectNode payload = (ObjectNode) objectMapper.readTree(result.payloadJson());
		payload.put("retry", failures == 1);
		if (failures > 1) {
			payload.put("hint", "이 정보 없이 지금까지 확인한 내용으로 답하세요.");
		}
		return payload.toString();
	}

	/**
	 * 루프 1회의 산출물.
	 *
	 * @param brandId  모델이 처음 넘긴 brandId 인자 - 로그 분석에서 "어느 브랜드 질문인가"를 가른다.
	 * @param answered true면 실제로 생성된 답변 텍스트다(FALLBACK_ANSWER·BLOCKED_ANSWER가 아님) -
	 *                 호출부(컨트롤러)가 이 값으로 followUps 생성 여부를 가른다(설계 §요구 "답변이
	 *                 실패/차단/폴백 문구면 호출 자체를 생략").
	 */
	public record AgentOutcome(String answer, List<String> referencedShortCodes, List<ReferenceInfo> references,
			List<AiChatLogEntry.ToolCallLog> toolCalls, int promptTokens, int outputTokens,
			Long brandId, String outcome, boolean answered) {
	}

	/** 참조 1건(FE 변경요청서 §7) - label은 라벨 조립 결과 문자열(형(type)·후속 매핑은 컨트롤러 몫). */
	public record ReferenceInfo(String shortCode, String label) {
	}
}
