package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.BrandStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** brand_account 접점 — username UNIQUE가 멱등 키다(같은 계정 재가입은 같은 행 재활성). */
@Repository
public class BrandRepository {

	private final JdbcTemplate db;

	public BrandRepository(JdbcTemplate db) {
		this.db = db;
	}

	/**
	 * 등록 또는 재가입 — CLOSED 행이 있으면 ACTIVE로 재활성하고 프로필 관측값을 갱신한다.
	 * last_tracked_on을 null로 되돌리는 이유: 재가입 시점의 윈도우(90일)를 백필이 다시 채워야
	 * 하는데, 옛 값이 남으면 다음 스윕이 감지(1페이지)만 돌아 탈퇴 기간의 유입을 놓친다.
	 */
	public long insertOrReactivate(String username, String igUserId, Long followers, String biography) {
		return db.queryForObject("""
				INSERT INTO brand_account (username, ig_user_id, followers, biography)
				VALUES (?, ?, ?, ?)
				ON CONFLICT (username) DO UPDATE SET
				  ig_user_id = EXCLUDED.ig_user_id, followers = EXCLUDED.followers,
				  biography = EXCLUDED.biography, status = 'ACTIVE', closed_at = NULL,
				  last_tracked_on = NULL, registered_at = now()
				RETURNING id""",
				Long.class, username, igUserId, followers, biography);
	}

	public Optional<BrandRow> findByUsername(String username) {
		return db.query("""
				SELECT id, username, ig_user_id, status, last_tracked_on
				FROM brand_account WHERE username = ?""",
				BrandRepository::toRow, username).stream().findFirst();
	}

	public List<BrandRow> findActive() {
		return db.query("""
				SELECT id, username, ig_user_id, status, last_tracked_on
				FROM brand_account WHERE status = 'ACTIVE' ORDER BY id""",
				BrandRepository::toRow);
	}

	/** 탈퇴 — ACTIVE였던 행만 닫는다. @return 실제로 전이됐으면 true(이미 닫힘·미존재는 false). */
	public boolean close(String username) {
		return db.update("""
				UPDATE brand_account SET status = 'CLOSED', closed_at = now()
				WHERE username = ? AND status = 'ACTIVE'""", username) > 0;
	}

	public void touchTracked(long brandId, LocalDate on) {
		db.update("UPDATE brand_account SET last_tracked_on = ? WHERE id = ?", on, brandId);
	}

	private static BrandRow toRow(ResultSet rs, int i) throws SQLException {
		java.sql.Date tracked = rs.getDate("last_tracked_on");
		return new BrandRow(rs.getLong("id"), rs.getString("username"), rs.getString("ig_user_id"),
				BrandStatus.valueOf(rs.getString("status")),
				tracked == null ? null : tracked.toLocalDate());
	}
}
