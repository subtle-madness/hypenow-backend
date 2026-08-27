package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * app.ai_chat_logs 적재·집계 통합 검증(설계 §6) - tool_calls jsonb 왕복과 일일 상한 판정용
 * countSince가 유저 스코프로 갈리는지가 핵심이다.
 */
class AiChatLogRepositoryIntegrationTest extends IntegrationTest {

	@Autowired
	JdbcClient jdbcClient;
	@Autowired
	ObjectMapper objectMapper;

	AiChatLogRepository repository;
	long userId;
	long otherUserId;

	@BeforeEach
	void setUp() {
		repository = new AiChatLogRepository(jdbcClient, objectMapper);
		jdbcClient.sql("TRUNCATE app.ai_chat_logs RESTART IDENTITY CASCADE").update();
		userId = insertUser();
		otherUserId = insertUser();
	}

	private long insertUser() {
		return jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role, name, user_type,
				                       agreed_terms, agreed_privacy, agreed_age14)
				VALUES (:email, 'hash', 'USER', '테스터', 'brand', true, true, true)
				RETURNING id
				""")
				.param("email", UUID.randomUUID() + "@example.com")
				.query(Long.class)
				.single();
	}

	@Test
	void 툴_시퀀스와_토큰이_담긴_로그가_그대로_적재된다() {
		AiChatLogEntry entry = new AiChatLogEntry(userId, 100L, "지난주 반응 좋은 게시물 알려줘", "3건이 있어요.",
				List.of(new AiChatLogEntry.ToolCallLog("list_posts",
						objectMapper.createObjectNode().put("brandId", 100).put("days", 7), 3)),
				1200, 340, 4200L, AiChatLogEntry.OUTCOME_OK);

		repository.insert(entry);

		String toolCalls = jdbcClient.sql("SELECT tool_calls::text FROM app.ai_chat_logs WHERE user_id = :id")
				.param("id", userId).query(String.class).single();
		assertThat(toolCalls).contains("list_posts").contains("\"rows\": 3");
		assertThat(jdbcClient.sql("SELECT prompt_tokens FROM app.ai_chat_logs WHERE user_id = :id")
				.param("id", userId).query(Integer.class).single()).isEqualTo(1200);
	}

	@Test
	void countSince는_해당_유저의_기간_내_행만_센다() {
		OffsetDateTime since = OffsetDateTime.now().minusHours(1);
		repository.insert(logOf(userId));
		repository.insert(logOf(userId));
		repository.insert(logOf(otherUserId));

		assertThat(repository.countSince(userId, since)).isEqualTo(2);
		assertThat(repository.countSince(otherUserId, since)).isEqualTo(1);
		assertThat(repository.countSince(userId, OffsetDateTime.now().plusMinutes(1))).isZero();
	}

	@Test
	void 마이그레이션이_일일_상한_기준값_30을_시드한다() {
		assertThat(jdbcClient.sql("SELECT value FROM app.app_setting WHERE key = 'ai.chat.daily-limit'")
				.query(String.class).optional()).contains("30");
	}

	private AiChatLogEntry logOf(long id) {
		return new AiChatLogEntry(id, null, "질문", "답변", List.of(), 10, 5, 100L,
				AiChatLogEntry.OUTCOME_OK);
	}
}
