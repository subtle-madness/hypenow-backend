package com.celfit.was.setting;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** app.app_setting(V6) 조회 — 캐시 없이 매번 SELECT(값 교체 즉시 반영이 요구사항, 호출 빈도 낮음). */
@Repository
public class AppSettingRepository {

	private final JdbcClient jdbcClient;

	public AppSettingRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public Optional<String> findValue(String key) {
		return jdbcClient.sql("SELECT value FROM app.app_setting WHERE key = :key")
				.param("key", key)
				.query(String.class)
				.optional();
	}
}
