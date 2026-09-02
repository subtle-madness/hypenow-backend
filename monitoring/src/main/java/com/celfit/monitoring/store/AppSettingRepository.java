package com.celfit.monitoring.store;

import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 런타임 설정 key-value(app_setting). monitoring은 JPA 없음 — JdbcTemplate 관용구. */
@Repository
public class AppSettingRepository {

	private final JdbcTemplate db;

	public AppSettingRepository(JdbcTemplate db) {
		this.db = db;
	}

	public Optional<String> find(String key) {
		try {
			return Optional.ofNullable(
					db.queryForObject("SELECT value FROM app_setting WHERE key = ?", String.class, key));
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	public void upsert(String key, String value) {
		db.update("""
				INSERT INTO app_setting (key, value) VALUES (?, ?)
				ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
				""", key, value);
	}
}
