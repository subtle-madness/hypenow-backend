package com.celfit.was.monitoring;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.monitoring_digests CRUD(v3, V15, 6.32) — 데일리 다이제스트 저장·조회·읽음 처리.
 * 생성은 {@link #upsert}(DigestJob, 갭 문서 A-1-2) — 워터마크 없이 날짜 재계산이라 재실행마다
 * items를 덮어써도 안전하다(created_at·read_at은 SET 절에 없어 자연히 보존).
 */
@Repository
public class DigestRepository {

	private final JdbcClient jdbcClient;

	public DigestRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * (user, date) 재계산 upsert — 이미 있으면 items만 덮어쓴다(늦게 도착한 alarm_event 반영,
	 * DigestJob 재실행 안전). created_at·read_at은 SET 절에 없어 자연히 보존된다. ON CONFLICT DO
	 * UPDATE는 항상 행을 반환하므로(신규든 갱신이든) id는 항상 존재 — Optional이 필요 없다.
	 */
	public long upsert(long userId, LocalDate digestDate, String itemsJson) {
		return jdbcClient.sql("""
				INSERT INTO app.monitoring_digests (user_id, digest_date, items)
				VALUES (:userId, :digestDate, CAST(:items AS jsonb))
				ON CONFLICT (user_id, digest_date) DO UPDATE SET items = EXCLUDED.items
				RETURNING id
				""")
				.param("userId", userId)
				.param("digestDate", digestDate)
				.param("items", itemsJson)
				.query(Long.class)
				.single();
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
