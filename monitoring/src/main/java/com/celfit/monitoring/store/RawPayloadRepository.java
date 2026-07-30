package com.celfit.monitoring.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** raw.fetch_payload 테이블 접점 — 응답 원문 적재(감사·재파싱용, 롤링 삭제 대상). */
@Repository
public class RawPayloadRepository {

	private final JdbcTemplate db;

	public RawPayloadRepository(JdbcTemplate db) {
		this.db = db;
	}

	/** kind는 PROFILE/POSTS/POST, subject는 username 또는 short_code. */
	public void save(String kind, String subject, int httpStatus, String payloadJson) {
		db.update("""
				INSERT INTO raw.fetch_payload (kind, subject, http_status, payload)
				VALUES (?, ?, ?, ?::jsonb)""",
				kind, subject, httpStatus, payloadJson);
	}
}
