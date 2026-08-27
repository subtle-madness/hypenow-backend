package com.celfit.was.v1.brandmonitoring.ai;

import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
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

	public void insert(AiChatLogEntry entry) {
		try {
			jdbcClient.sql("""
					INSERT INTO app.ai_chat_logs (user_id, brand_id, question, answer, tool_calls,
					                              prompt_tokens, output_tokens, elapsed_ms, outcome)
					VALUES (:userId, :brandId, :question, :answer, CAST(:toolCalls AS jsonb),
					        :promptTokens, :outputTokens, :elapsedMs, :outcome)
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
					.update();
		} catch (RuntimeException e) {
			log.warn("AI 질문 로그 적재 실패(무시) - userId={}, outcome={}", entry.userId(), entry.outcome(), e);
		}
	}

	/** since 이후 이 유저가 던진 질문 수 - 일일 상한 판정 전용(설계 §7). */
	public int countSince(long userId, OffsetDateTime since) {
		return jdbcClient.sql("""
				SELECT count(*) FROM app.ai_chat_logs
				WHERE user_id = :userId AND created_at >= :since
				""")
				.param("userId", userId)
				.param("since", since)
				.query(Integer.class)
				.single();
	}
}
