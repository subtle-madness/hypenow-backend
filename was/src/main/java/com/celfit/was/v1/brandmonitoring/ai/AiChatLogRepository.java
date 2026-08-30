package com.celfit.was.v1.brandmonitoring.ai;

import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 어시스턴트 질문 로그 적재·집계(설계 §6). append-only - UPDATE·DELETE 경로를 두지 않는다.
 *
 * <p>적재는 fire-and-forget이다(SignupEventRecorder 선례): 이미 좋은 답을 만든 요청을 로그 실패로
 * 500으로 만들 이유가 없다. 대신 이 테이블이 일일 상한의 원장이기도 해서, 적재가 실패하면 그 요청은
 * 상한 계산에서 빠진다 - PoC 규모에서 감수하는 트레이드오프이며 warn 로그로 관측 가능하게 둔다.
 */
@Repository
public class AiChatLogRepository {

	private static final Logger log = LoggerFactory.getLogger(AiChatLogRepository.class);

	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	public AiChatLogRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	/**
	 * 생성된 로그 행의 id를 돌려준다(대화 상세 조립에서 messageId로 쓴다, FE 변경요청서 §8). 적재
	 * 실패는 여전히 fire-and-forget이라 예외를 던지지 않고 null을 돌려준다 - 호출부는 null을
	 * "이 요청은 상한 계산·대화 갱신에서 빠졌다"는 신호로 받아들이면 된다.
	 */
	public Long insert(AiChatLogEntry entry) {
		try {
			return jdbcClient.sql("""
					INSERT INTO app.ai_chat_logs (user_id, brand_id, question, answer, tool_calls,
					                              prompt_tokens, output_tokens, elapsed_ms, outcome,
					                              conversation_id, preset_id, scope, follow_ups, refs)
					VALUES (:userId, :brandId, :question, :answer, CAST(:toolCalls AS jsonb),
					        :promptTokens, :outputTokens, :elapsedMs, :outcome,
					        :conversationId, :presetId, CAST(:scope AS jsonb),
					        CAST(:followUps AS jsonb), CAST(:refs AS jsonb))
					RETURNING id
					""")
					.param("userId", entry.userId())
					.param("brandId", entry.brandId())
					.param("question", entry.question())
					.param("answer", entry.answer())
					.param("toolCalls", objectMapper.writeValueAsString(entry.toolCalls()))
					.param("promptTokens", entry.promptTokens())
					.param("outputTokens", entry.outputTokens())
					.param("elapsedMs", (int) Math.min(entry.elapsedMillis(), Integer.MAX_VALUE))
					.param("outcome", entry.outcome())
					.param("conversationId", entry.conversationId())
					.param("presetId", entry.presetId())
					.param("scope", entry.scope() == null ? null : objectMapper.writeValueAsString(entry.scope()))
					.param("followUps", objectMapper.writeValueAsString(entry.followUps()))
					.param("refs", objectMapper.writeValueAsString(entry.refs()))
					.query(Long.class)
					.single();
		} catch (RuntimeException e) {
			log.warn("AI 질문 로그 적재 실패(무시) - userId={}, outcome={}", entry.userId(), entry.outcome(), e);
			return null;
		}
	}

	/**
	 * since 이후 이 유저가 던진 질문 수 - 일일 상한 판정 전용(설계 §7). {@code llm_failed}는 제외한다
	 * (FE 변경요청서 §9.1) - 서버 쪽 실패(타임아웃·5xx)까지 사용자 상한에서 차감하면 부당하다.
	 */
	public int countSince(long userId, OffsetDateTime since) {
		return jdbcClient.sql("""
				SELECT count(*) FROM app.ai_chat_logs
				WHERE user_id = :userId AND created_at >= :since AND outcome != :excludedOutcome
				""")
				.param("userId", userId)
				.param("since", since)
				.param("excludedOutcome", AiChatLogEntry.OUTCOME_LLM_FAILED)
				.query(Integer.class)
				.single();
	}

	/** 대화 상세 조립(FE 변경요청서 §8) 전용 - conversationId에 속한 로그 전부를 시간순으로 돌려준다. */
	public List<ConversationMessageRow> findByConversation(long conversationId) {
		return jdbcClient.sql("""
				SELECT question, answer, preset_id, follow_ups, refs, created_at
				FROM app.ai_chat_logs
				WHERE conversation_id = :conversationId
				ORDER BY created_at ASC
				""")
				.param("conversationId", conversationId)
				.query((rs, rowNum) -> new ConversationMessageRow(
						rs.getString("question"),
						rs.getString("answer"),
						rs.getString("preset_id"),
						objectMapper.readTree(rs.getString("follow_ups")),
						objectMapper.readTree(rs.getString("refs")),
						rs.getObject("created_at", OffsetDateTime.class)))
				.list();
	}

	/**
	 * 대화 상세 응답 조립용 행(설계 §8) - 로그 원장 전체(AiChatLogEntry)가 아니라 메시지 펼침에 필요한
	 * 컬럼만 담는다. answer가 null이면 그 질문에는 아직(또는 끝내) 답변이 없었다는 뜻 -
	 * 컨트롤러는 이 경우 assistant 메시지를 만들지 않는다.
	 */
	public record ConversationMessageRow(String question, String answer, String presetId,
			JsonNode followUps, JsonNode refs, OffsetDateTime createdAt) {
	}
}
