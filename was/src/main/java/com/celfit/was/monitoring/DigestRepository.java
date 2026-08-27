package com.celfit.was.monitoring;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * app.monitoring_digests CRUD(v3, V15, 6.32) — 다이제스트 저장·조회·읽음 처리. 생성은 원래
 * (일별) DigestJob이 썼던 {@link #upsert}와 그 후신인 주간 WeeklyDigestJob 전용
 * {@link #upsertWeekly} 둘로 갈라져 있다 — 워터마크 없이 재계산이라 재실행마다 items를 덮어써도
 * 안전하다(created_at·read_at은 기본적으로 SET 절에 없어 자연히 보존).
 */
@Repository
public class DigestRepository {

	private final JdbcClient jdbcClient;

	public DigestRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * (user, date) 재계산 upsert — 이미 있으면 items만 덮어쓴다(늦게 도착한 이벤트 반영, 재실행
	 * 안전). created_at·read_at은 SET 절에 없어 자연히 보존된다. ON CONFLICT DO UPDATE는 항상
	 * 행을 반환하므로(신규든 갱신이든) id는 항상 존재 — Optional이 필요 없다.
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

	/**
	 * 주간 전용 (user, 주 시작일) upsert(2026-08-28 품질 리뷰 C2) — 구 일일 DigestJob이 같은
	 * digest_date(달력일)에 만들어 둔 행과의 충돌을 리셋한다.
	 *
	 * <p>주간 다이제스트는 항상 그 주 창이 닫힌 뒤(다음 월요일, {@code windowCloseAt})에만
	 * 생성·갱신된다 — 그래서 기존 행의 created_at이 windowCloseAt보다 <b>이전</b>이면, 그 행은
	 * 이번 주간 잡이 만든 게 아니라 <b>같은 달력 날짜(월요일)에 구 일일 잡이 만들어 둔 레거시 행</b>일
	 * 수밖에 없다 — read_at을 NULL로, created_at을 now()로 리셋해 "새로 만들어진 미읽음 다이제스트"로
	 * 만든다. 반대로 created_at이 windowCloseAt 이후면 이 upsert 자신(또는 같은 주의 이전 실행)이
	 * 만든 정당한 행이므로 그대로 보존한다 — 같은 주 재실행 시 read_at·created_at 보존 계약은
	 * 그대로 유지된다. windowCloseAt은 호출부가 {@code WeekWindow#toExclusive()}로 넘긴다.
	 */
	public long upsertWeekly(long userId, LocalDate weekStart, OffsetDateTime windowCloseAt, String itemsJson) {
		return jdbcClient.sql("""
				INSERT INTO app.monitoring_digests AS d (user_id, digest_date, items)
				VALUES (:userId, :digestDate, CAST(:items AS jsonb))
				ON CONFLICT (user_id, digest_date) DO UPDATE SET
				    items = EXCLUDED.items,
				    created_at = CASE WHEN d.created_at < :windowCloseAt THEN now() ELSE d.created_at END,
				    read_at    = CASE WHEN d.created_at < :windowCloseAt THEN NULL ELSE d.read_at END
				RETURNING id
				""")
				.param("userId", userId)
				.param("digestDate", weekStart)
				.param("windowCloseAt", windowCloseAt)
				.param("items", itemsJson)
				.query(Long.class)
				.single();
	}

	/**
	 * (user, date) 행 삭제(2026-08-28 품질 리뷰 I4) — 이번 실행의 조립 결과가 비면(킬 스위치
	 * off·브랜드 연결 해제 등) 이전 실행이 만들어 둔 행을 걷어낸다. 행이 없으면 no-op.
	 */
	public void delete(long userId, LocalDate digestDate) {
		jdbcClient.sql("DELETE FROM app.monitoring_digests WHERE user_id = :userId AND digest_date = :digestDate")
				.param("userId", userId)
				.param("digestDate", digestDate)
				.update();
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
