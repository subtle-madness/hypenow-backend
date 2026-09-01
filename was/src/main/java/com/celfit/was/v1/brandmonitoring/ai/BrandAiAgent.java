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
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 에이전트 루프(설계 §3) - 시스템 프롬프트 + 대화 이력을 LLM에 보내고, 툴 호출이 오면 실행해
 * 되먹이기를 반복하다 텍스트 답변이 나오면 끝낸다. 서버 세션은 스코프 밖이다(설계 §10) - 다만 SSE
 * 스트리밍(FE 변경요청서 §3.2, T3)은 {@link #run(long, List, Long, AiScope, String, StreamListener,
 * BooleanSupplier)} 오버로드로 지원한다 - 정지 조건·툴 실행·되먹임 로직은 완결 경로와 완전히 같고
 * LLM 호출만 스트리밍으로 받는다({@link StreamListener} 참조).
 *
 * <p>정지 조건이 네 겹이다(C2/I6, 한계 재도출 2026-08-31 - 스펙 §4) - 툴 호출
 * {@value #MAX_TOOL_CALLS}회(정상 질문은 닿지 않는 폭주 방지 안전망으로 강등 - 1차 제약은 시간·토큰
 * 둘이다), 누적 프롬프트 토큰 {@value #PROMPT_TOKEN_BUDGET}(비용 뿌리 질문당 천장 ~50원의 직접
 * 환산, 댓글 본문 무절단×매 턴 전체 재전송의 O(k²) 폭발 방지, I6), 벽시계 예산
 * {@value #TIME_BUDGET_MILLIS}ms(시간 뿌리 90초에서 마무리 여유를 뺀 값 - 1 LLM 호출 최악 92초까지
 * 걸릴 수 있어 안전망 12회를 다 채우면 수십 분이 걸리는 것을 막는다) 중 하나라도 걸리면 다음 턴을 툴
 * 호출 불가로 보내 답변을 강제한다. 강제 답변이 일어난 원인은 {@link AgentOutcome#limitReached()}로
 * 노출한다({@value #LIMIT_BUDGET}=툴 상한·토큰 예산, {@value #LIMIT_TIME}=벽시계 예산, 스펙 §5).
 * <b>강제 답변 턴은 1회로 제한한다(C2 잔여, 2026-08-28 재리뷰)</b> - mode=NONE인데도 모델이
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

	/** 툴 호출 안전망(한계 재도출 2026-08-31, 스펙 §4) - 1차 제약이 아니라 폭주 방지선이다. 정상
	 * 질문은 시간·토큰 예산이 먼저 끊으므로 이 상한에는 원래 닿지 않는다(구 상한 8회는 회수를 1차
	 * 제약으로 뒀던 시절 값 - 싼 툴을 회수 상한이 먼저 끊는 배신이 있었다). */
	static final int MAX_TOOL_CALLS = 24;
	/** LLM 호출 안전망(M2) - 강제 답변 턴 1회 제한(C2 잔여)이 정상적으로는 항상 먼저 걸리므로 여기
	 * 도달은 이제 이론상으로만 남은 최후 방어선이다. 왕복은 고정 오버헤드가 커서 시간·토큰이 먼저
	 * 끊는 게 정상이라 한계 재도출(스펙 §4)에서도 12회를 그대로 유지한다. */
	static final int MAX_LLM_CALLS = 12;
	/** 누적 프롬프트 토큰 예산(I6, 한계 재도출 2026-08-31 - 스펙 §4) - 비용 뿌리(질문당 비용 천장
	 * ~50원)를 직접 환산한 값이다. 댓글 본문 무절단 × 매 턴 전체 재전송이면 O(k²)로 토큰이 터진다 -
	 * 절단(BrandAiToolbox)과 별개로 루프 차원의 두 번째 방어선이다. */
	static final int PROMPT_TOKEN_BUDGET = 100_000;
	/** 벽시계 예산(C2, 한계 재도출 2026-08-31 - 스펙 §4) - 시간 뿌리 90초(컨트롤러 응답 계약)에서
	 * 마무리 여유를 뺀 값이다. Vertex 요청 타임아웃을 45초로 줄여도(BrandAiConfig) 재시도 1회를
	 * 더하면 최악 92초까지 걸릴 수 있어, 이 예산이 소진되면 그 한 번의 호출을 끝으로 더 부르지 않는다. */
	static final long TIME_BUDGET_MILLIS = 85_000L;
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

	/** {@link AgentOutcome#limitReached()} 값 - 구조 고지(스펙 §5)용. 툴 상한·토큰 예산 소진이 원인이면
	 * budget, 벽시계 예산 소진이 원인이면 time. */
	public static final String LIMIT_BUDGET = "budget";
	public static final String LIMIT_TIME = "time";

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
		return run(userId, messages, null, null, "", List.of());
	}

	/** 기존 4-인자 관용구 유지(호환, F1 이전 호출부·단발 테스트 전용) - brandId 제한 없음과 동일하다. */
	public AgentOutcome run(long userId, List<AiChatMessage> messages, AiScope scope, String extraSystemPrompt) {
		return run(userId, messages, null, scope, extraSystemPrompt, List.of());
	}

	/**
	 * 시스템 프롬프트 조립(스펙 §5·§6) - {@link BrandAiGlossary#SECTION}(용어 사전)은 세션 brandId
	 * 유무와 무관하게 항상 실린다(스펙 §5, 프리셋과 무관 전 질문 적용). 세션 brandId가 있으면 그
	 * 뒤에 브랜드 컨텍스트를 이어 선주입한다. 두 run() 오버로드(완결·스트리밍)가 이 헬퍼를 공유해
	 * 조립 로직이 갈리지 않게 한다.
	 */
	private String buildBasePrompt(long userId, Long sessionBrandId, String extraSystemPrompt) {
		String context = sessionBrandId == null ? "" : toolbox.brandContextLine(userId, sessionBrandId);
		return BrandAiPrompt.SYSTEM + BrandAiGlossary.SECTION + context
				+ (extraSystemPrompt == null ? "" : extraSystemPrompt);
	}

	/**
	 * 프리셋 verified 플랜 선실행 주입(스펙 §6, Genie Trusted Assets 패턴의 우리식 적용) - 에이전트
	 * 루프에 진입하기 전에 검증된 호출을 먼저 실행해 functionCall/functionResponse 쌍으로 대화에
	 * 주입한다. 핵심 수치가 검증된 호출에서 나오므로 프리셋 질문은 툴 선택·인자 조합을 틀릴 수 없다.
	 * 이후 루프는 기존과 동일하게 진행되고, 모델은 주입된 결과 위에서 답하되 필요하면 추가 조회도
	 * 할 수 있다.
	 *
	 * <p>brandId는 플랜에 하드코딩돼 있지 않다 - 이 세션의 sessionBrandId로 매 호출 인자에 실행 시점에
	 * 채워 넣는다. 플랜 실행이 실패하면(예: 소유 검증·인자 오류) 그 지점에서 멈추고 <b>이미 주입된
	 * 선행 호출은 그대로 유지한 채</b> 기존 자유 경로로 폴백한다 - 이미 성공한 선행 호출까지 무효화할
	 * 이유가 없다. 선실행분도 tool_calls 로그·shortCode 회수에 그대로 포함해 관측 일관성을 지킨다.
	 *
	 * @param listener 스트리밍 경로면 각 선실행 호출 직전에 {@link StreamListener#onToolCall}을
	 *                 통지해 FE 진행 표시를 일반 툴 호출과 동일하게 유지한다. 완결 경로는 null을 넘긴다.
	 */
	private void injectPlannedCalls(List<BrandAiPresets.PlannedCall> plannedCalls,
			BrandAiToolbox.ToolSession toolSession, long userId, Long sessionBrandId, List<JsonNode> contents,
			List<AiChatLogEntry.ToolCallLog> toolCalls, LinkedHashSet<String> shortCodes, StreamListener listener) {
		for (BrandAiPresets.PlannedCall call : plannedCalls) {
			ObjectNode args = (ObjectNode) objectMapper.readTree(call.argsJson());
			if (sessionBrandId != null) {
				args.put("brandId", sessionBrandId);
			}
			if (listener != null) {
				listener.onToolCall(call.toolName(), toolCalls.size() + 1);
			}
			AiToolResult result = toolbox.execute(toolSession, userId, call.toolName(), args);
			if (result.failed()) {
				log.warn("프리셋 플랜 선실행 실패 - toolName={}, userId={}", call.toolName(), userId);
				break;
			}
			toolCalls.add(new AiChatLogEntry.ToolCallLog(call.toolName(), args, result.rowCount()));
			shortCodes.addAll(result.shortCodes());
			contents.add(client.modelToolCallContent(List.of(new LlmTurn.ToolCall(call.toolName(), args))));
			contents.add(client.toolResultContent(
					List.of(new GeminiChatClient.ToolResponse(call.toolName(), result.payloadJson()))));
		}
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
	 * @param plannedCalls       프리셋 verified 플랜(스펙 §6) - 루프 진입 전에 선실행해 결과를 대화에
	 *                           주입한다. 빈 목록이면 기존 자유 경로와 동일하다.
	 */
	public AgentOutcome run(long userId, List<AiChatMessage> messages, Long sessionBrandId, AiScope scope,
			String extraSystemPrompt, List<BrandAiPresets.PlannedCall> plannedCalls) {
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
		String basePrompt = buildBasePrompt(userId, sessionBrandId, extraSystemPrompt);
		// 모델이 실제로 조회한 brandId(로그용, AgentOutcome#brandId) - 위 sessionBrandId(대화 스코프
		// 강제용)와는 목적이 달라 별도 변수로 관리한다.
		Long brandId = null;
		int promptTokens = 0;
		int outputTokens = 0;
		long deadline = clock.millis() + TIME_BUDGET_MILLIS;

		injectPlannedCalls(plannedCalls, toolSession, userId, sessionBrandId, contents, toolCalls, shortCodes, null);

		for (int llmCall = 1; llmCall <= MAX_LLM_CALLS; llmCall++) {
			// 매 호출 전에 남은 예산을 확인한다(C2) - 부족하면 이번 호출을 마지막으로 삼아 답변을 강제한다.
			// 원인을 보존한다(한계 재도출 2026-08-31, 스펙 §5) - 툴 상한·토큰 예산 소진은 budget, 벽시계
			// 예산 소진은 time. AgentOutcome#limitReached로 그대로 노출한다.
			boolean toolCapped = toolCalls.size() >= MAX_TOOL_CALLS;
			String limitCause = toolCapped || promptTokens >= PROMPT_TOKEN_BUDGET ? LIMIT_BUDGET
					: clock.millis() >= deadline ? LIMIT_TIME : null;
			boolean capped = limitCause != null;
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
							AiChatLogEntry.OUTCOME_BLOCKED, false, null);
				}
				boolean hasRealAnswer = !turn.text().isBlank();
				String answer = hasRealAnswer ? turn.text() : FALLBACK_ANSWER;
				List<String> referenced = referencedIn(answer, shortCodes);
				return new AgentOutcome(answer, referenced, buildReferences(toolSession, referenced),
						List.copyOf(toolCalls), promptTokens, outputTokens, brandId,
						capped ? AiChatLogEntry.OUTCOME_TOOL_CAP : AiChatLogEntry.OUTCOME_OK, hasRealAnswer,
						capped ? limitCause : null);
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
						AiChatLogEntry.OUTCOME_LLM_CALL_CAP, false, limitCause);
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
				false, LIMIT_BUDGET);
	}

	/**
	 * 스트리밍 리스너(T3, FE 변경요청서 §3.2) - 텍스트 델타·툴 호출 시작을 실시간으로 통지한다.
	 *
	 * <p><b>홀드백 불변식</b> - {@link #onAnswerDelta}로 넘어오는 텍스트는 항상 최종 답변의 일부임이
	 * 보장된다. 툴이 선언된 일반 턴(강제 답변 턴이 아닌 턴)은 텍스트를 누적만 하다가 그 턴이
	 * functionCall 없이 순수 텍스트로 끝난(=최종 답변으로 확정된) 시점에만 방출한다. 강제 답변 턴
	 * (toolConfig NONE)은 그 턴 자체가 항상 최종 답변이므로 델타 도착 즉시 방출한다. 이 불변식이
	 * 없으면 같은 턴에서 텍스트를 먼저 흘린 뒤 functionCall이 나오는 경우(Gemini가 parts 배열에
	 * 텍스트+functionCall을 함께 담아 반환할 수 있다) 클라이언트 화면에 고아 텍스트가 남는다 -
	 * {@link #run(long, List, Long, AiScope, String, StreamListener, BooleanSupplier)}가 이 규칙을
	 * 강제한다({@code BrandAiAgentTest}의 홀드백 테스트로 고정).
	 *
	 * <p><b>알려진 예외</b> - 강제 답변 턴(mode=NONE)인데도 모델이 텍스트와 functionCall을 같은 턴에
	 * 함께 돌려주는 병리적 응답은 이 불변식을 깨뜨릴 수 있다(텍스트는 이미 라이브로 나갔는데 그 턴이
	 * FALLBACK_ANSWER로 대체되는 경우) - 강제 답변 턴을 라이브 방출하는 설계 자체의 트레이드오프이고,
	 * 비스트리밍 경로도 이 조합을 이미 병리 사례로 취급해 warn을 남긴다(위 클래스 상단 주석 C2 잔여).
	 */
	public interface StreamListener {

		/** 방출 규칙은 위 홀드백 불변식을 따른다. */
		void onAnswerDelta(String textDelta);

		/** 툴 실행을 시작하는 시점에 1회 호출한다. index는 이번 run() 전체에서 몇 번째 툴 호출인지(1부터). */
		void onToolCall(String toolName, int index);
	}

	/**
	 * 스트리밍 오버로드(T3, FE 변경요청서 §3.2) - SSE 응답용. 정지 조건·툴 실행·재시도 되먹임은
	 * {@link #run(long, List, Long, AiScope, String)}과 완전히 동일하고, 차이는 둘뿐이다: (1) LLM
	 * 호출을 {@link GeminiChatClient#generateStream}으로 바꿔 청크 단위로 텍스트를 받아
	 * {@link StreamListener}의 홀드백 불변식을 지키며 방출하고, (2) 매 LLM 호출 직전과 각 툴 실행
	 * 직전에 {@code abortSignal}을 확인해 참이면 즉시 {@link AiChatLogEntry#OUTCOME_ABORTED}로
	 * 멈춘다(T4 - 컨트롤러가 SSE emitter의 onError/onCompletion/onTimeout에서 세운 플래그를 넘긴다).
	 * 진행 중인 HTTP 호출 자체를 강제 중단하지는 않는다 - 다음 확인 지점에서 멈추는 협조적 취소다.
	 *
	 * @param plannedCalls 프리셋 verified 플랜(스펙 §6) - 완결 경로와 동일하게 루프 진입 전에 선실행해
	 *                     결과를 대화에 주입한다. 선실행 각 호출 전에 {@link StreamListener#onToolCall}도
	 *                     통지해 FE 진행 표시를 일반 툴 호출과 동일하게 유지한다.
	 */
	public AgentOutcome run(long userId, List<AiChatMessage> messages, Long sessionBrandId, AiScope scope,
			String extraSystemPrompt, List<BrandAiPresets.PlannedCall> plannedCalls, StreamListener listener,
			BooleanSupplier abortSignal) {
		List<JsonNode> contents = new ArrayList<>();
		for (AiChatMessage message : messages) {
			contents.add(AiChatMessage.ROLE_ASSISTANT.equals(message.role())
					? client.modelContent(message.content())
					: client.userContent(message.content()));
		}

		List<AiChatLogEntry.ToolCallLog> toolCalls = new ArrayList<>();
		LinkedHashSet<String> shortCodes = new LinkedHashSet<>();
		Map<String, Integer> failuresByTool = new HashMap<>();
		BrandAiToolbox.ToolSession toolSession = new BrandAiToolbox.ToolSession(scope, sessionBrandId);
		String basePrompt = buildBasePrompt(userId, sessionBrandId, extraSystemPrompt);
		Long brandId = null;
		int promptTokens = 0;
		int outputTokens = 0;
		long deadline = clock.millis() + TIME_BUDGET_MILLIS;

		injectPlannedCalls(plannedCalls, toolSession, userId, sessionBrandId, contents, toolCalls, shortCodes,
				listener);

		for (int llmCall = 1; llmCall <= MAX_LLM_CALLS; llmCall++) {
			if (abortSignal.getAsBoolean()) {
				return abortedOutcome(userId, toolCalls, promptTokens, outputTokens, brandId);
			}

			// 원인을 보존한다(한계 재도출 2026-08-31, 스펙 §5) - 비스트리밍 경로와 동일한 분기.
			boolean toolCapped = toolCalls.size() >= MAX_TOOL_CALLS;
			String limitCause = toolCapped || promptTokens >= PROMPT_TOKEN_BUDGET ? LIMIT_BUDGET
					: clock.millis() >= deadline ? LIMIT_TIME : null;
			boolean capped = limitCause != null;
			String systemPrompt = !capped ? basePrompt
					: basePrompt + (toolCapped ? BrandAiPrompt.TOOL_CAP_NOTE : BrandAiPrompt.TIME_BUDGET_NOTE);

			// 강제 답변 턴(capped)만 라이브 방출한다 - 일반 턴은 순수 텍스트로 끝난 게 확정될 때까지
			// 누적만 한다(홀드백 불변식, StreamListener 상단 주석).
			boolean liveEmit = capped;
			LlmTurn turn = client.generateStream(systemPrompt, contents, BrandAiToolSpecs.ALL, capped, chunk -> {
				if (liveEmit && !chunk.textDelta().isEmpty()) {
					listener.onAnswerDelta(chunk.textDelta());
				}
			});
			promptTokens += turn.promptTokens();
			outputTokens += turn.outputTokens();

			if (turn.toolCalls().isEmpty()) {
				if (turn.text().isBlank() && isBlocked(turn.finishReason())) {
					// 텍스트가 비었으니 liveEmit이었어도 아직 아무 것도 안 나갔다 - 항상 여기서 방출한다.
					listener.onAnswerDelta(BLOCKED_ANSWER);
					List<String> blockedReferenced = referencedIn(BLOCKED_ANSWER, shortCodes);
					return new AgentOutcome(BLOCKED_ANSWER, blockedReferenced,
							buildReferences(toolSession, blockedReferenced),
							List.copyOf(toolCalls), promptTokens, outputTokens, brandId,
							AiChatLogEntry.OUTCOME_BLOCKED, false, null);
				}
				boolean hasRealAnswer = !turn.text().isBlank();
				String answer = hasRealAnswer ? turn.text() : FALLBACK_ANSWER;
				// 홀드백 확정 시점 - 일반 턴이 순수 텍스트로 끝났다 = 최종 답변 확정. 강제 답변 턴은
				// 이미 라이브로 다 내보냈으니(liveEmit=true) 여기서 다시 보내면 중복이라 건너뛴다.
				if (!liveEmit) {
					listener.onAnswerDelta(answer);
				}
				List<String> referenced = referencedIn(answer, shortCodes);
				return new AgentOutcome(answer, referenced, buildReferences(toolSession, referenced),
						List.copyOf(toolCalls), promptTokens, outputTokens, brandId,
						capped ? AiChatLogEntry.OUTCOME_TOOL_CAP : AiChatLogEntry.OUTCOME_OK, hasRealAnswer,
						capped ? limitCause : null);
			}

			if (capped) {
				// 알려진 예외(StreamListener 상단 주석) - 이 턴에서 이미 라이브로 나간 텍스트가 있었다면
				// (병리적으로 텍스트+functionCall이 함께 온 경우) 여기서 FALLBACK_ANSWER를 또 보내는 게
				// 홀드백 불변식과 어긋날 수 있다. 비스트리밍 경로도 이 조합을 병리로 보고 warn만 남긴다.
				log.warn("AI 에이전트 강제 답변 턴에서도 툴 호출만 반환(스트리밍) - userId={}, 툴 호출 {}회", userId,
						toolCalls.size());
				listener.onAnswerDelta(FALLBACK_ANSWER);
				List<String> referenced = referencedIn(FALLBACK_ANSWER, shortCodes);
				return new AgentOutcome(FALLBACK_ANSWER, referenced, buildReferences(toolSession, referenced),
						List.copyOf(toolCalls), promptTokens, outputTokens, brandId,
						AiChatLogEntry.OUTCOME_LLM_CALL_CAP, false, limitCause);
			}

			contents.add(client.modelToolCallContent(turn.toolCalls()));
			List<GeminiChatClient.ToolResponse> responses = new ArrayList<>();
			for (LlmTurn.ToolCall call : turn.toolCalls()) {
				if (abortSignal.getAsBoolean()) {
					return abortedOutcome(userId, toolCalls, promptTokens, outputTokens, brandId);
				}
				if (toolCalls.size() >= MAX_TOOL_CALLS) {
					responses.add(new GeminiChatClient.ToolResponse(call.name(),
							objectMapper.createObjectNode().put("error", "조회 가능 횟수를 모두 썼습니다.")
									.put("retry", false).toString()));
					continue;
				}
				listener.onToolCall(call.name(), toolCalls.size() + 1);
				AiToolResult result = toolbox.execute(toolSession, userId, call.name(), call.args());
				toolCalls.add(new AiChatLogEntry.ToolCallLog(call.name(), call.args(), result.rowCount()));
				shortCodes.addAll(result.shortCodes());
				if (brandId == null && !result.failed() && call.args().hasNonNull("brandId")) {
					brandId = call.args().path("brandId").asLong();
				}
				responses.add(new GeminiChatClient.ToolResponse(call.name(),
						result.failed() ? withRetryHint(call.name(), result, failuresByTool)
								: result.payloadJson()));
			}
			contents.add(client.toolResultContent(responses));
		}

		log.warn("AI 에이전트 LLM 호출 안전망 도달(스트리밍) - userId={}, 툴 호출 {}회", userId, toolCalls.size());
		listener.onAnswerDelta(FALLBACK_ANSWER);
		List<String> referenced = referencedIn(FALLBACK_ANSWER, shortCodes);
		return new AgentOutcome(FALLBACK_ANSWER, referenced, buildReferences(toolSession, referenced),
				List.copyOf(toolCalls), promptTokens, outputTokens, brandId, AiChatLogEntry.OUTCOME_LLM_CALL_CAP,
				false, LIMIT_BUDGET);
	}

	/** 중단 산출물(T3/T4) - abortSignal이 참이 된 시점의 부분 정보만 담는다. answer는 null(F2 관용구와
	 * 동형 - LLM 실패류는 answer 없음), answered=false라 컨트롤러가 followUps를 생략한다. */
	private static AgentOutcome abortedOutcome(long userId, List<AiChatLogEntry.ToolCallLog> toolCalls,
			int promptTokens, int outputTokens, Long brandId) {
		log.info("AI 에이전트 중단(클라이언트 연결 끊김) - userId={}, 툴 호출 {}회", userId, toolCalls.size());
		return new AgentOutcome(null, List.of(), List.of(), List.copyOf(toolCalls), promptTokens, outputTokens,
				brandId, AiChatLogEntry.OUTCOME_ABORTED, false, null);
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
	 * @param answered      true면 실제로 생성된 답변 텍스트다(FALLBACK_ANSWER·BLOCKED_ANSWER가 아님) -
	 *                      호출부(컨트롤러)가 이 값으로 followUps 생성 여부를 가른다(설계 §요구 "답변이
	 *                      실패/차단/폴백 문구면 호출 자체를 생략").
	 * @param limitReached  강제 답변이 일어난 원인(한계 재도출 2026-08-31, 스펙 §5) -
	 *                      {@value #LIMIT_BUDGET}(툴 상한·토큰 예산) 또는 {@value #LIMIT_TIME}(벽시계
	 *                      예산), 정상 완료·BLOCKED·ABORTED면 null. 컨트롤러가 이 값으로 FE 구조 고지
	 *                      필드를 채운다.
	 */
	public record AgentOutcome(String answer, List<String> referencedShortCodes, List<ReferenceInfo> references,
			List<AiChatLogEntry.ToolCallLog> toolCalls, int promptTokens, int outputTokens,
			Long brandId, String outcome, boolean answered, String limitReached) {
	}

	/** 참조 1건(FE 변경요청서 §7) - label은 라벨 조립 결과 문자열(형(type)·후속 매핑은 컨트롤러 몫). */
	public record ReferenceInfo(String shortCode, String label) {
	}
}
