package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.CandidateStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** detected_candidate 테이블 접점 — 후보 생성(멱등)·조회·검토 상태 전이. */
@Repository
public class CandidateRepository {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final RowMapper<CandidateRow> ROW = (rs, i) -> new CandidateRow(
			rs.getLong("id"), rs.getLong("target_id"), rs.getString("short_code"),
			CandidateStatus.valueOf(rs.getString("status")));

	private final JdbcTemplate db;

	public CandidateRepository(JdbcTemplate db) {
		this.db = db;
	}

	/**
	 * 재감지로 같은 게시물이 다시 걸려도 행은 하나 — 거절된 후보가 되살아나지 않는다.
	 * matchedKeywords는 v1.1 신규(계약 §3) — null이면 컬럼도 null(was가 빈 배열로 폴백).
	 */
	public void insertPending(long targetId, String shortCode, String captionExcerpt,
			List<String> matchedKeywords) {
		db.update("""
				INSERT INTO detected_candidate (target_id, short_code, caption_excerpt, status, matched_keywords)
				VALUES (?, ?, ?, 'PENDING', ?::jsonb)
				ON CONFLICT (target_id, short_code) DO NOTHING""",
				targetId, shortCode, captionExcerpt,
				matchedKeywords == null ? null : JSON.writeValueAsString(matchedKeywords));
	}

	public Optional<CandidateRow> find(long id) {
		return db.query("SELECT * FROM detected_candidate WHERE id = ?", ROW, id).stream().findFirst();
	}

	public void setStatus(long id, CandidateStatus status) {
		db.update("UPDATE detected_candidate SET status=? WHERE id=?", status.name(), id);
	}
}
