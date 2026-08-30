package com.celfit.was.v1.brandmonitoring.ai;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.ai_conversations CRUD(FE 변경요청서 2026-08-28 §8) - 대화 목록·상세·소프트 삭제.
 *
 * <p>모든 조회·수정은 반드시 userId 스코프를 걸어 남의 대화를 건드리지 못하게 한다(브랜드 모니터링
 * 사용자 격리 관용구 - 등록자 원장 정본과 동일 원칙). 삭제는 하드 삭제가 아니라 deleted_at 마킹이다 -
 * app.ai_chat_logs가 conversation_id로 이 테이블을 참조하므로 하드 삭제하면 로그 쪽 참조가 끊긴다.
 */
@Repository
public class AiConversationRepository {

	/** 대화 제목(첫 사용자 발화) 절단 길이 - 컬럼 자체는 절단 없이도 담기지만, 목록 화면 제목으로는
	 * 이 길이면 충분하고 과도하게 긴 원문을 그대로 쌓아두지 않는다. */
	private static final int TITLE_MAX_LENGTH = 200;

	private final JdbcClient jdbcClient;

	public AiConversationRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/** 새 대화를 만들고 id를 돌려준다. title은 200자로 절단해 저장한다. */
	public long create(long userId, long brandId, String title) {
		String truncated = title == null ? "" : title.strip();
		if (truncated.length() > TITLE_MAX_LENGTH) {
			truncated = truncated.substring(0, TITLE_MAX_LENGTH);
		}
		return jdbcClient.sql("""
				INSERT INTO app.ai_conversations (user_id, brand_id, title)
				VALUES (:userId, :brandId, :title)
				RETURNING id
				""")
				.param("userId", userId)
				.param("brandId", brandId)
				.param("title", truncated)
				.query(Long.class)
				.single();
	}

	/** 본인 소유이고 삭제되지 않은 대화만 돌려준다 - 남의 대화·삭제된 대화는 빈 Optional(컨트롤러가 404로 매핑). */
	public Optional<ConversationRow> findOwnedActive(long id, long userId) {
		return jdbcClient.sql("""
				SELECT id, brand_id, title, updated_at
				FROM app.ai_conversations
				WHERE id = :id AND user_id = :userId AND deleted_at IS NULL
				""")
				.param("id", id)
				.param("userId", userId)
				.query((rs, rowNum) -> new ConversationRow(rs.getLong("id"), rs.getLong("brand_id"),
						rs.getString("title"), rs.getObject("updated_at", OffsetDateTime.class)))
				.optional();
	}

	/** 새 질문이 붙을 때마다 updated_at을 갱신한다 - 목록 정렬이 최근 활동 순이라서다. */
	public void touch(long id) {
		jdbcClient.sql("UPDATE app.ai_conversations SET updated_at = now() WHERE id = :id")
				.param("id", id)
				.update();
	}

	/**
	 * 유저·브랜드 스코프의 활성 대화 목록(최근 활동순). messageCount는 그 대화에 딸린
	 * app.ai_chat_logs 행 수 × 2(질문+답변 쌍)로 서브쿼리 집계한다 - 단 answer가 아직 없는 행은
	 * 질문만 센다(+1). 별도 카운터 컬럼을 두지 않고 매번 집계하는 이유는 로그 테이블(app.ai_chat_logs)이
	 * 이미 원장이라 카운터를 이중 관리할 이유가 없어서다(AiChatQuota의 일일 상한과 동일 판단).
	 */
	public List<ConversationSummaryRow> list(long userId, long brandId, int limit) {
		return jdbcClient.sql("""
				SELECT c.id, c.title, c.updated_at,
				       COALESCE((SELECT SUM(CASE WHEN l.answer IS NULL THEN 1 ELSE 2 END)
				                 FROM app.ai_chat_logs l
				                 WHERE l.conversation_id = c.id), 0) AS message_count
				FROM app.ai_conversations c
				WHERE c.user_id = :userId AND c.brand_id = :brandId AND c.deleted_at IS NULL
				ORDER BY c.updated_at DESC
				LIMIT :limit
				""")
				.param("userId", userId)
				.param("brandId", brandId)
				.param("limit", limit)
				.query((rs, rowNum) -> new ConversationSummaryRow(rs.getLong("id"), rs.getString("title"),
						rs.getObject("updated_at", OffsetDateTime.class), rs.getInt("message_count")))
				.list();
	}

	/** 본인 소유·미삭제 대화만 삭제 처리한다. 영향 행 수(0 또는 1)를 돌려줘 컨트롤러가 404를 가릴 수 있게 한다. */
	public int softDelete(long id, long userId) {
		return jdbcClient.sql("""
				UPDATE app.ai_conversations SET deleted_at = now()
				WHERE id = :id AND user_id = :userId AND deleted_at IS NULL
				""")
				.param("id", id)
				.param("userId", userId)
				.update();
	}

	/** 대화 상세 조회 결과(소유 검증 완료 후) - brandId는 accountIds 응답 필드 조립에 쓴다. */
	public record ConversationRow(long id, long brandId, String title, OffsetDateTime updatedAt) {
	}

	/** 대화 목록 응답 1건 조립용 행. */
	public record ConversationSummaryRow(long id, String title, OffsetDateTime updatedAt, int messageCount) {
	}
}
