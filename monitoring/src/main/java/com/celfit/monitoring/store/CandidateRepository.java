package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.CandidateStatus;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * detected_candidate 테이블 접점 — **신규 적재는 v2에서 중단됐다**(승인 플로우 폐지, 스펙 §2-2).
 * 테이블과 기존 행은 이력으로 남고, DROP은 참조가 완전히 끊긴 다음 릴리스의 contract 단계 몫이다.
 * 지금 이 클래스에 호출자는 없다 — 테이블이 살아 있는 동안의 저장소 계약만 유지한다.
 */
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
