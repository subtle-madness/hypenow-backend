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
import tools.jackson.databind.node.JsonNodeFactory;

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
		jdbcClient.sql("TRUNCATE app.ai_chat_logs, app.ai_conversations RESTART IDENTITY CASCADE").update();
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
	void countSince는_llm_failed_행을_제외한다() {
		// 서버 실패는 사용자 상한에서 차감하지 않는다(FE 변경요청서 §9.1)
		OffsetDateTime since = OffsetDateTime.now().minusHours(1);
		repository.insert(logOf(userId));
		repository.insert(new AiChatLogEntry(userId, null, "실패한 질문", null, List.of(), 10, 0, 100L,
				AiChatLogEntry.OUTCOME_LLM_FAILED));

		assertThat(repository.countSince(userId, since)).isEqualTo(1);
	}

	@Test
	void 마이그레이션이_일일_상한_기준값_30을_시드한다() {
		assertThat(jdbcClient.sql("SELECT value FROM app.app_setting WHERE key = 'ai.chat.daily-limit'")
				.query(String.class).optional()).contains("30");
	}

	@Test
	void 마이그레이션이_분당_한도_기준값_5를_시드한다() {
		assertThat(jdbcClient.sql("SELECT value FROM app.app_setting WHERE key = 'ai.chat.per-minute-limit'")
				.query(String.class).optional()).contains("5");
	}

	@Test
	void insert는_생성된_행의_id를_돌려준다() {
		Long id = repository.insert(logOf(userId));

		assertThat(id).isNotNull();
		assertThat(jdbcClient.sql("SELECT count(*) FROM app.ai_chat_logs WHERE id = :id")
				.param("id", id).query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void 대화_연결_필드와_프리셋_범위_후속질문_참조가_그대로_왕복한다() {
		var followUps = JsonNodeFactory.instance.arrayNode().add("다음엔 뭘 물어볼까요?");
		var refs = JsonNodeFactory.instance.arrayNode().add("ABC123");
		var scope = objectMapper.createObjectNode().put("days", 7);
		Long conversationId = insertConversation();
		AiChatLogEntry entry = new AiChatLogEntry(userId, 100L, "질문", "답변", List.of(), 1, 1, 1L,
				AiChatLogEntry.OUTCOME_OK, conversationId, "preset-a", scope, followUps, refs);

		Long id = repository.insert(entry);

		assertThat(id).isNotNull();
		List<AiChatLogRepository.ConversationMessageRow> rows = repository.findByConversation(conversationId);
		assertThat(rows).hasSize(1);
		AiChatLogRepository.ConversationMessageRow row = rows.get(0);
		assertThat(row.question()).isEqualTo("질문");
		assertThat(row.answer()).isEqualTo("답변");
		assertThat(row.presetId()).isEqualTo("preset-a");
		assertThat(row.followUps().toString()).contains("다음엔 뭘 물어볼까요?");
		assertThat(row.refs().toString()).contains("ABC123");
	}

	@Test
	void findByConversation은_시간순으로_돌려준다() {
		Long conversationId = insertConversation();
		repository.insert(logWithConversation(conversationId, "첫 질문"));
		repository.insert(logWithConversation(conversationId, "둘째 질문"));

		List<AiChatLogRepository.ConversationMessageRow> rows = repository.findByConversation(conversationId);

		assertThat(rows).extracting(AiChatLogRepository.ConversationMessageRow::question)
				.containsExactly("첫 질문", "둘째 질문");
	}

	private Long insertConversation() {
		return jdbcClient.sql("""
				INSERT INTO app.ai_conversations (user_id, brand_id, title)
				VALUES (:userId, 100, '대화')
				RETURNING id
				""")
				.param("userId", userId)
				.query(Long.class)
				.single();
	}

	private AiChatLogEntry logWithConversation(Long conversationId, String question) {
		return new AiChatLogEntry(userId, 100L, question, "답변", List.of(), 1, 1, 1L,
				AiChatLogEntry.OUTCOME_OK, conversationId, null, null,
				JsonNodeFactory.instance.arrayNode(), JsonNodeFactory.instance.arrayNode());
	}

	private AiChatLogEntry logOf(long id) {
		return new AiChatLogEntry(id, null, "질문", "답변", List.of(), 10, 5, 100L,
				AiChatLogEntry.OUTCOME_OK);
	}
}
