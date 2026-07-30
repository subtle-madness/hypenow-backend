package com.celfit.was.monitoring;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.monitoring_digests CRUD(v3, V15, 6.32) — 데일리 다이제스트 저장·조회·읽음 처리.
 * 생성(insertIfAbsent)은 이 태스크에서는 재실행 안전한 기반만 두고, 실제 9시 크론 호출은
 * 후속 태스크(다이제스트 생성) 범위다.
 */
@Repository
public class DigestRepository {

	private final JdbcClient jdbcClient;

	public DigestRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * (user, date) 유니크 위반 시 조용히 무시 — 크론이 같은 날짜를 재실행해도 안전. 삽입되면
	 * id를 담아 반환하고, 이미 존재하면 빈 Optional(ON CONFLICT DO NOTHING이라 RETURNING 행이 없다).
	 */
	public Optional<Long> insertIfAbsent(long userId, LocalDate digestDate, String itemsJson) {
		return jdbcClient.sql("""
				INSERT INTO app.monitoring_digests (user_id, digest_date, items)
				VALUES (:userId, :digestDate, CAST(:items AS jsonb))
				ON CONFLICT (user_id, digest_date) DO NOTHING
				RETURNING id
				""")
				.param("userId", userId)
				.param("digestDate", digestDate)
				.param("items", itemsJson)
				.query(Long.class)
				.optional();
	}

	/** 최근 다이제스트 — digest_date DESC(id DESC tie-break). limit은 컨트롤러가 넘기는 상한(6.32는 30건). */
	public List<DigestRow> findRecentByUser(long userId, int limit) {
		return jdbcClient.sql("""
				SELECT id, user_id, digest_date, items::text AS items_json, created_at, read_at
				FROM app.monitoring_digests
				WHERE user_id = :userId
				ORDER BY digest_date DESC, id DESC
				LIMIT :limit
				""")
				.param("userId", userId)
				.param("limit", limit)
				.query(DigestRow.class)
				.list();
	}

	public long countByUser(long userId) {
		return jdbcClient.sql("SELECT count(*) FROM app.monitoring_digests WHERE user_id = :userId")
				.param("userId", userId)
				.query(Long.class)
				.single();
	}

	/**
	 * 본인 소유 행만 읽음 처리 — 존재하지 않는 id·타 유저 id는 WHERE 절이 그냥 걸러내고, 이미 읽은
	 * 행은 read_at IS NULL 조건이 걸러내 최초 읽음 시각을 보존한다(멱등). 빈 리스트는 IN () SQL
	 * 오류를 피하려 no-op.
	 */
	public void markRead(long userId, List<Long> ids) {
		if (ids.isEmpty()) {
			return;
		}
		jdbcClient.sql("""
				UPDATE app.monitoring_digests
				SET read_at = now()
				WHERE user_id = :userId AND id IN (:ids) AND read_at IS NULL
				""")
				.param("userId", userId)
				.param("ids", ids)
				.update();
	}

	/** 안읽음 전체 읽음 처리 — 응답 창(최근 30건) 제한 없이 유저 전체 대상(6.32 all=true). */
	public void markAllRead(long userId) {
		jdbcClient.sql("""
				UPDATE app.monitoring_digests
				SET read_at = now()
				WHERE user_id = :userId AND read_at IS NULL
				""")
				.param("userId", userId)
				.update();
	}
}
