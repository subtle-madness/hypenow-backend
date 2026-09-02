package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * app.ai_conversations CRUD 통합 검증(FE 변경요청서 2026-08-28 §8) - 생성·목록(messageCount 집계
 * 포함)·소프트 삭제·유저 격리가 핵심이다.
 */
class AiConversationRepositoryIntegrationTest extends IntegrationTest {

	@Autowired
	JdbcClient jdbcClient;
	@Autowired
	ObjectMapper objectMapper;

	AiConversationRepository conversationRepository;
	AiChatLogRepository logRepository;
	long userId;
	long otherUserId;

	@BeforeEach
	void setUp() {
		conversationRepository = new AiConversationRepository(jdbcClient);
		logRepository = new AiChatLogRepository(jdbcClient, objectMapper);
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
	void 생성한_대화를_본인_스코프로_조회할_수_있다() {
		long id = conversationRepository.create(userId, 100L, "지난주 반응 좋은 게시물 알려줘");

		Optional<AiConversationRepository.ConversationRow> found =
				conversationRepository.findOwnedActive(id, userId);

		assertThat(found).isPresent();
		assertThat(found.get().brandId()).isEqualTo(100L);
		assertThat(found.get().title()).isEqualTo("지난주 반응 좋은 게시물 알려줘");
	}

	@Test
	void 제목은_200자로_절단된다() {
		String longTitle = "가".repeat(250);
		long id = conversationRepository.create(userId, 100L, longTitle);

		String stored = conversationRepository.findOwnedActive(id, userId).orElseThrow().title();

		assertThat(stored).hasSize(200);
	}

	@Test
	void 타_유저는_조회할_수_없다() {
		long id = conversationRepository.create(userId, 100L, "질문");

		assertThat(conversationRepository.findOwnedActive(id, otherUserId)).isEmpty();
	}

	@Test
	void 목록의_messageCount는_로그_행수의_2배다_단_답변없는_행은_1로_센다() {
		long conversationId = conversationRepository.create(userId, 100L, "질문");
		insertLog(conversationId, "질문1", "답변1");
		insertLog(conversationId, "질문2", "답변2");
		insertLog(conversationId, "질문3", null);

		List<AiConversationRepository.ConversationSummaryRow> rows =
				conversationRepository.list(userId, 100L, 20);

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).messageCount()).isEqualTo(5);
	}

	@Test
	void 목록은_브랜드_스코프로_갈리고_최신_활동순이다() {
		long conv1 = conversationRepository.create(userId, 100L, "브랜드100 대화");
		long conv2 = conversationRepository.create(userId, 200L, "브랜드200 대화");
		conversationRepository.touch(conv1);

		List<AiConversationRepository.ConversationSummaryRow> brand100 =
				conversationRepository.list(userId, 100L, 20);
		List<AiConversationRepository.ConversationSummaryRow> brand200 =
				conversationRepository.list(userId, 200L, 20);

		assertThat(brand100).extracting(AiConversationRepository.ConversationSummaryRow::id)
				.containsExactly(conv1);
		assertThat(brand200).extracting(AiConversationRepository.ConversationSummaryRow::id)
				.containsExactly(conv2);
	}

	@Test
	void 소프트_삭제_후에는_목록과_상세_조회에서_사라진다() {
		long id = conversationRepository.create(userId, 100L, "질문");

		int affected = conversationRepository.softDelete(id, userId);

		assertThat(affected).isEqualTo(1);
		assertThat(conversationRepository.findOwnedActive(id, userId)).isEmpty();
		assertThat(conversationRepository.list(userId, 100L, 20)).isEmpty();
	}

	@Test
	void 타_유저의_대화는_삭제할_수_없다() {
		long id = conversationRepository.create(userId, 100L, "질문");

		int affected = conversationRepository.softDelete(id, otherUserId);

		assertThat(affected).isZero();
		assertThat(conversationRepository.findOwnedActive(id, userId)).isPresent();
	}

	@Test
	void 이미_삭제된_대화를_다시_삭제하면_0행이다() {
		long id = conversationRepository.create(userId, 100L, "질문");
		conversationRepository.softDelete(id, userId);

		assertThat(conversationRepository.softDelete(id, userId)).isZero();
	}

	private void insertLog(long conversationId, String question, String answer) {
		Long conversationIdBoxed = conversationId;
		logRepository.insert(new AiChatLogEntry(userId, 100L, question, answer, List.of(), 1, 1, 1L,
				answer == null ? AiChatLogEntry.OUTCOME_LLM_FAILED : AiChatLogEntry.OUTCOME_OK,
				conversationIdBoxed, null, null,
				tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode(),
				tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode()));
	}
}
