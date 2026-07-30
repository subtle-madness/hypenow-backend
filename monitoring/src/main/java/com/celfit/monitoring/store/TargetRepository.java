package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
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
			rs.getLong("id"), rs.getObject("user_id", Long.class),
			TargetType.valueOf(rs.getString("type")),
			rs.getString("username"), rs.getString("short_code"),
			rs.getString("keyword_rule") == null ? null
					: JSON.readValue(rs.getString("keyword_rule"), KeywordRule.class),
			TargetStatus.valueOf(rs.getString("status")), rs.getString("tracked_short_code"),
			rs.getString("registration_key"),
			rs.getTimestamp("expires_at").toInstant(), rs.getString("fail_reason"),
			// NOT NULL DEFAULT now() — 애플리케이션이 값을 주지 않는 컬럼이라 null 분기가 없다.
			rs.getTimestamp("registered_at").toInstant(),
			rs.getTimestamp("tracked_hidden_at") == null ? null
					: rs.getTimestamp("tracked_hidden_at").toInstant(),
			rs.getBoolean("fetch_failing"));

	private final JdbcTemplate db;

	public TargetRepository(JdbcTemplate db) {
		this.db = db;
	}

	/** userId는 nullable — V3 이전 등록분과의 호환을 위해 열어 두지만, 신규 등록은 API가 필수로 막는다. */
	public long insert(TargetType type, Long userId, String username, String shortCode, KeywordRule rule,
			TargetStatus status, String trackedShortCode, String registrationKey, Instant expiresAt) {
		// tracked_since는 tracked_short_code가 있을 때만 채운다.
		// IS NOT NULL 자리의 파라미터는 ::text 캐스팅이 필수 — 없으면 PG가 타입을 못 정해
		// "could not determine data type of parameter"로 실패한다.
		return db.queryForObject("""
				INSERT INTO target (type, user_id, username, short_code, keyword_rule, status,
				                    tracked_short_code, tracked_since, registration_key, expires_at)
				VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, CASE WHEN ?::text IS NOT NULL THEN now() END, ?, ?)
				RETURNING id""",
				Long.class, type.name(), userId, username, shortCode,
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

	/** matchedKeywords는 감지 자동 전환 시점에 실제 매칭된 키워드 원문(계약 §3 target.matched_keywords). */
	public void markTracking(long id, String shortCode, List<String> matchedKeywords) {
		db.update("""
				UPDATE target SET status='TRACKING', tracked_short_code=?, tracked_since=now(),
				                   matched_keywords=?::jsonb
				WHERE id=?""",
				shortCode, JSON.writeValueAsString(matchedKeywords), id);
	}

	public void close(long id, TargetStatus terminal, String failReason) {
		db.update("UPDATE target SET status=?, closed_at=now(), fail_reason=? WHERE id=?",
				terminal.name(), failReason, id);
	}

	public void updateExpiresAt(long id, Instant expiresAt) {
		db.update("UPDATE target SET expires_at=? WHERE id=?", Timestamp.from(expiresAt), id);
	}

	/**
	 * 결정적 수집 불가(SUBJECT_NOT_FOUND·PRIVATE_ACCOUNT) 신호 — status는 건드리지 않는다(v2.2, 계약
	 * §3 status 의미 개정). 이미 hidden이면(직전 값이 non-null) 이 UPDATE는 0행을 갱신한다 —
	 * 반환값이 "방금 전이됐는가"라 호출부가 이걸로 알람 1회 발화를 판단한다(재발화 방지, 계약 §3
	 * alarm_event CONTENT_UNAVAILABLE 발화 시점).
	 *
	 * @return 이 호출로 null→값 전이가 실제로 일어났으면 true.
	 */
	public boolean markHidden(long id) {
		int updated = db.update(
				"UPDATE target SET tracked_hidden_at = now() WHERE id = ? AND tracked_hidden_at IS NULL", id);
		return updated > 0;
	}

	/**
	 * 일시 수집 오류가 당일 재시도 라운드 소진까지 해소되지 않은 target들을 fetch_failing=true로
	 * 세팅한다 — 이미 failing인 target은 갱신 대상에서 빠지므로(false→true 전이만) **반환되는 행만**
	 * 방금 전이된 것이고, 이게 FETCH_FAILED 알람 1회 발화의 근거다(계약 §3 alarm_event 발화 시점).
	 * ids가 비었으면 쿼리 없이 빈 목록을 돌려준다.
	 */
	public List<FailingTarget> markFetchFailing(Collection<Long> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return db.query("""
				UPDATE target SET fetch_failing = true
				WHERE id = ANY(?) AND fetch_failing = false
				RETURNING id, user_id, username, tracked_short_code""",
				ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids.toArray())),
				(rs, i) -> new FailingTarget(rs.getLong("id"), rs.getObject("user_id", Long.class),
						rs.getString("username"), rs.getString("tracked_short_code")));
	}

	/**
	 * 수집 성공 신호 — v2.2부터 last_fetched_at 갱신과 함께 hidden·error 신호를 동시에 복귀시킨다
	 * (계약 §3 target.tracked_hidden_at·fetch_failing "수집 성공 시 복귀"). "이 target의 대상 수집이
	 * 성공했다"는 사실 자체가 재공개·복구 판정이라, 별도 API 없이 이 지점 하나가 유일한 복귀 지점이다.
	 */
	public void touchFetched(long id) {
		db.update("""
				UPDATE target SET last_fetched_at = now(), fetch_failing = false, tracked_hidden_at = NULL
				WHERE id = ?""", id);
	}

	/**
	 * 만료 스윕 — 활성 상태만 EXPIRED로 종결하고 **종결된 행을 돌려준다**.
	 * RETURNING이 필요한 이유: 만료는 이 UPDATE가 유일한 발생 지점이라, 반환이 없으면
	 * "방금 무엇이 끝났는지"를 다시 알아낼 방법이 없다(closed_at 시각 재조회는 경합에 취약).
	 */
	public List<ExpiredTarget> expireOverdue() {
		return db.query("""
				UPDATE target SET status='EXPIRED', closed_at=now()
				WHERE status IN ('WATCHING','TRACKING') AND expires_at < now()
				RETURNING id, user_id, username, tracked_short_code""",
				(rs, i) -> new ExpiredTarget(rs.getLong("id"), rs.getObject("user_id", Long.class),
						rs.getString("username"), rs.getString("tracked_short_code")));
	}

	/** 이 게시물을 추적 중인 활성 캠페인 — 지표 비공개 알람의 수신자(스냅샷은 캠페인 간 공유라 N건일 수 있다). */
	public List<TargetOwner> findTrackingOwners(String shortCode) {
		return db.query("""
				SELECT id, user_id, username FROM target
				WHERE tracked_short_code = ? AND status IN ('WATCHING','TRACKING')""",
				(rs, i) -> new TargetOwner(rs.getLong("id"), rs.getObject("user_id", Long.class),
						rs.getString("username")),
				shortCode);
	}
}
