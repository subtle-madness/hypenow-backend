package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** target 테이블 접점 — 등록·활성 조회·상태 전이·만료 스윕. */
@Repository
public class TargetRepository {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final RowMapper<TargetRow> ROW = (rs, i) -> new TargetRow(
			rs.getLong("id"), TargetType.valueOf(rs.getString("type")),
			rs.getString("username"), rs.getString("short_code"),
			rs.getString("keyword_rule") == null ? null
					: JSON.readValue(rs.getString("keyword_rule"), KeywordRule.class),
			TargetStatus.valueOf(rs.getString("status")), rs.getString("tracked_short_code"),
			rs.getString("registration_key"),
			rs.getTimestamp("expires_at").toInstant(), rs.getString("fail_reason"),
			// NOT NULL DEFAULT now() — 애플리케이션이 값을 주지 않는 컬럼이라 null 분기가 없다.
			rs.getTimestamp("registered_at").toInstant());

	private final JdbcTemplate db;

	public TargetRepository(JdbcTemplate db) {
		this.db = db;
	}

	public long insert(TargetType type, String username, String shortCode, KeywordRule rule,
			TargetStatus status, String trackedShortCode, String registrationKey, Instant expiresAt) {
		// tracked_since는 tracked_short_code가 있을 때만 채운다.
		// IS NOT NULL 자리의 파라미터는 ::text 캐스팅이 필수 — 없으면 PG가 타입을 못 정해
		// "could not determine data type of parameter"로 실패한다.
		return db.queryForObject("""
				INSERT INTO target (type, username, short_code, keyword_rule, status,
				                    tracked_short_code, tracked_since, registration_key, expires_at)
				VALUES (?, ?, ?, ?::jsonb, ?, ?, CASE WHEN ?::text IS NOT NULL THEN now() END, ?, ?)
				RETURNING id""",
				Long.class, type.name(), username, shortCode,
				rule == null ? null : JSON.writeValueAsString(rule), status.name(),
				trackedShortCode, trackedShortCode, registrationKey,
				Timestamp.from(expiresAt));
	}

	public Optional<TargetRow> findByRegistrationKey(String key) {
		return db.query("SELECT * FROM target WHERE registration_key = ?", ROW, key).stream().findFirst();
	}

	public Optional<TargetRow> findById(long id) {
		return db.query("SELECT * FROM target WHERE id = ?", ROW, id).stream().findFirst();
	}

	public List<TargetRow> findActive() {
		return db.query("SELECT * FROM target WHERE status IN ('WATCHING','TRACKING') ORDER BY id", ROW);
	}

	public void markTracking(long id, String shortCode) {
		db.update("UPDATE target SET status='TRACKING', tracked_short_code=?, tracked_since=now() WHERE id=?",
				shortCode, id);
	}

	public void close(long id, TargetStatus terminal, String failReason) {
		db.update("UPDATE target SET status=?, closed_at=now(), fail_reason=? WHERE id=?",
				terminal.name(), failReason, id);
	}

	public void updateExpiresAt(long id, Instant expiresAt) {
		db.update("UPDATE target SET expires_at=? WHERE id=?", Timestamp.from(expiresAt), id);
	}

	public void touchFetched(long id) {
		db.update("UPDATE target SET last_fetched_at=now() WHERE id=?", id);
	}

	/** 만료 스윕 — 활성 상태만 EXPIRED로 종결. */
	public int expireOverdue() {
		return db.update("""
				UPDATE target SET status='EXPIRED', closed_at=now()
				WHERE status IN ('WATCHING','TRACKING') AND expires_at < now()""");
	}
}
