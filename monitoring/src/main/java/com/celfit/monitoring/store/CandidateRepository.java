package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.CandidateStatus;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** detected_candidate 테이블 접점 — 후보 생성(멱등)·조회·검토 상태 전이. */
@Repository
public class CandidateRepository {

	private static final RowMapper<CandidateRow> ROW = (rs, i) -> new CandidateRow(
			rs.getLong("id"), rs.getLong("target_id"), rs.getString("short_code"),
			CandidateStatus.valueOf(rs.getString("status")));

	private final JdbcTemplate db;

	public CandidateRepository(JdbcTemplate db) {
		this.db = db;
	}

	/** 재감지로 같은 게시물이 다시 걸려도 행은 하나 — 거절된 후보가 되살아나지 않는다. */
	public void insertPending(long targetId, String shortCode, String captionExcerpt) {
		db.update("""
				INSERT INTO detected_candidate (target_id, short_code, caption_excerpt, status)
				VALUES (?, ?, ?, 'PENDING')
				ON CONFLICT (target_id, short_code) DO NOTHING""",
				targetId, shortCode, captionExcerpt);
	}

	public Optional<CandidateRow> find(long id) {
		return db.query("SELECT * FROM detected_candidate WHERE id = ?", ROW, id).stream().findFirst();
	}

	public void setStatus(long id, CandidateStatus status) {
		db.update("UPDATE detected_candidate SET status=? WHERE id=?", status.name(), id);
	}
}
