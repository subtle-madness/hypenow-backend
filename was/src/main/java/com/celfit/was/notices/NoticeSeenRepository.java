package com.celfit.was.notices;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** app.notice_seen — 유저당 마지막 확인 시각 1행 upsert. */
@Repository
public class NoticeSeenRepository {

	private final JdbcClient jdbcClient;

	public NoticeSeenRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public Optional<OffsetDateTime> findLastSeenAt(long userId) {
		return jdbcClient.sql("""
				SELECT last_seen_at
				FROM app.notice_seen
				WHERE user_id = :userId
				""")
				.param("userId", userId)
				.query(OffsetDateTime.class)
				.optional();
	}

	/** PUT 계약 — 받은 값을 그대로 저장한다(역행 가드 없음, 요청서 "저장만 하면 됩니다"). */
	public void upsert(long userId, OffsetDateTime lastSeenAt) {
		jdbcClient.sql("""
				INSERT INTO app.notice_seen (user_id, last_seen_at)
				VALUES (:userId, :lastSeenAt)
				ON CONFLICT (user_id) DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at
				""")
				.param("userId", userId)
				.param("lastSeenAt", lastSeenAt)
				.update();
	}
}
