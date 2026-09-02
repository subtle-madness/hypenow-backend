package com.celfit.was.v1.brandmonitoring.ai;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 브랜드 AI 챗 답변 피드백(👍👎) 저장·해제(2026-09-02, 골드셋 발굴 필터 - 스펙 2026-09-01 §2·§7-3).
 * app.ai_chat_logs 세 컬럼(feedback, feedback_comment, feedback_at)에 UPDATE를 건다 - 로그 원장은
 * append-only 원칙({@link AiChatLogRepository} 참고)이라 이 갱신 경로는 의도적으로 별도 리포지토리로
 * 분리했다.
 *
 * <p>소유 검증은 항상 userId 스코프로 건다(브랜드 모니터링 사용자 격리 관용구) - 남의 로그 행은
 * 존재 여부를 노출하지 않고 그대로 0행 갱신으로 떨어뜨린다(컨트롤러가 404로 매핑). 대화가
 * 소프트 삭제됐으면(app.ai_conversations.deleted_at) 그 아래 메시지도 대화 상세 조회와 동일하게
 * 404로 수렴시킨다 - conversation_id가 NULL인 행(대화에 안 묶인 옛 질문)은 이 검사 대상이 아니다.
 */
@Repository
public class AiChatFeedbackRepository {

	private final JdbcClient jdbcClient;

	public AiChatFeedbackRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * 피드백을 기록한다 - 같은 메시지에 다시 보내면 덮어쓴다(멱등). 대상 행이 없거나(존재하지 않음)
	 * 본인 소유가 아니거나 소속 대화가 삭제됐으면 빈 Optional(컨트롤러가 404로 매핑).
	 */
	public Optional<FeedbackRow> upsert(long messageId, long userId, String value, String comment) {
		return jdbcClient.sql("""
				UPDATE app.ai_chat_logs l
				SET feedback = :value, feedback_comment = :comment, feedback_at = now()
				WHERE l.id = :id AND l.user_id = :userId AND %s
				RETURNING l.feedback, l.feedback_comment, l.feedback_at
				""".formatted(OWNED_AND_NOT_DELETED))
				.param("id", messageId)
				.param("userId", userId)
				.param("value", value)
				.param("comment", comment)
				.query((rs, rowNum) -> new FeedbackRow(rs.getString("feedback"),
						rs.getString("feedback_comment"), rs.getObject("feedback_at", OffsetDateTime.class)))
				.optional();
	}

	/** 피드백 세 컬럼을 전부 null로 되돌린다. 영향 행 수(0 또는 1)를 돌려줘 컨트롤러가 404를 가릴 수 있게 한다. */
	public int clear(long messageId, long userId) {
		return jdbcClient.sql("""
				UPDATE app.ai_chat_logs l
				SET feedback = NULL, feedback_comment = NULL, feedback_at = NULL
				WHERE l.id = :id AND l.user_id = :userId AND %s
				""".formatted(OWNED_AND_NOT_DELETED))
				.param("id", messageId)
				.param("userId", userId)
				.update();
	}

	/** conversation_id가 NULL(대화 미연결 옛 행)이면 통과, 아니면 그 대화가 미삭제 상태여야 통과. */
	private static final String OWNED_AND_NOT_DELETED = """
			(l.conversation_id IS NULL OR EXISTS (
			     SELECT 1 FROM app.ai_conversations c
			     WHERE c.id = l.conversation_id AND c.deleted_at IS NULL))
			""";

	/** 피드백 저장 결과(PUT 응답 조립용) - value는 "up"/"down", comment는 선택 코멘트(없으면 null). */
	public record FeedbackRow(String value, String comment, OffsetDateTime at) {
	}
}
