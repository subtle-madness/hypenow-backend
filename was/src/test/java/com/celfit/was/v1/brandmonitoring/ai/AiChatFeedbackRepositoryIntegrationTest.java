package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 피드백 저장 컬럼 왕복 검증(2026-09-02 마이그레이션 V20260902120707 - feedback·feedback_comment·
 * feedback_at) - 저장·덮어쓰기·해제와, 소유·소프트 삭제 스코프가 실제 DB에서도 걸리는지가 핵심.
 */
class AiChatFeedbackRepositoryIntegrationTest extends IntegrationTest {

	@Autowired
	JdbcClient jdbcClient;

	AiChatFeedbackRepository repository;
	long userId;
	long otherUserId;

	@BeforeEach
	void setUp() {
		repository = new AiChatFeedbackRepository(jdbcClient);
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

	private long insertLog(long ownerId, Long conversationId) {
		return jdbcClient.sql("""
				INSERT INTO app.ai_chat_logs (user_id, question, answer, outcome, conversation_id)
				VALUES (:userId, '질문', '답변', 'ok', :conversationId)
				RETURNING id
				""")
				.param("userId", ownerId)
				.param("conversationId", conversationId)
				.query(Long.class)
				.single();
	}

	private long insertConversation(long ownerId, boolean deleted) {
		return jdbcClient.sql("""
				INSERT INTO app.ai_conversations (user_id, brand_id, title, deleted_at)
				VALUES (:userId, 100, '대화', %s)
				RETURNING id
				""".formatted(deleted ? "now()" : "NULL"))
				.param("userId", ownerId)
				.query(Long.class)
				.single();
	}

	@Test
	void 피드백을_저장하고_덮어쓴다() {
		long messageId = insertLog(userId, null);

		Optional<AiChatFeedbackRepository.FeedbackRow> first = repository.upsert(messageId, userId, "down", "왜 이래");
		Optional<AiChatFeedbackRepository.FeedbackRow> second = repository.upsert(messageId, userId, "up", null);

		assertThat(first).isPresent();
		assertThat(first.get().value()).isEqualTo("down");
		assertThat(first.get().comment()).isEqualTo("왜 이래");
		assertThat(second).isPresent();
		assertThat(second.get().value()).isEqualTo("up");
		assertThat(second.get().comment()).isNull();
	}

	@Test
	void 피드백을_해제하면_세_컬럼이_모두_null이다() {
		long messageId = insertLog(userId, null);
		repository.upsert(messageId, userId, "down", "코멘트");

		int updated = repository.clear(messageId, userId);

		assertThat(updated).isEqualTo(1);
		String values = jdbcClient.sql("""
				SELECT feedback, feedback_comment, feedback_at::text FROM app.ai_chat_logs WHERE id = :id
				""")
				.param("id", messageId)
				.query((rs, rowNum) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3))
				.single();
		assertThat(values).isEqualTo("null|null|null");
	}

	@Test
	void 남의_메시지는_저장도_해제도_적용되지_않는다() {
		long messageId = insertLog(otherUserId, null);

		assertThat(repository.upsert(messageId, userId, "up", null)).isEmpty();
		assertThat(repository.clear(messageId, userId)).isZero();
	}

	@Test
	void 삭제된_대화_아래_메시지는_저장도_해제도_적용되지_않는다() {
		long conversationId = insertConversation(userId, true);
		long messageId = insertLog(userId, conversationId);

		assertThat(repository.upsert(messageId, userId, "up", null)).isEmpty();
		assertThat(repository.clear(messageId, userId)).isZero();
	}

	@Test
	void 대화에_안_묶인_메시지는_소유자면_저장된다() {
		long messageId = insertLog(userId, null);

		Optional<AiChatFeedbackRepository.FeedbackRow> result = repository.upsert(messageId, userId, "down", null);

		assertThat(result).isPresent();
	}
}
