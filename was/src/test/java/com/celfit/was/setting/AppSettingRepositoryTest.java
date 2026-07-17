package com.celfit.was.setting;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Flyway V6가 만든 app.app_setting 실물 위에서 검증 — DDL 하드코딩 없음. */
class AppSettingRepositoryTest extends IntegrationTest {

	@Autowired
	AppSettingRepository repository;

	@Autowired
	JdbcClient jdbcClient;

	@Test
	void 시드된_signup_code는_빈_문자열이다() {
		assertThat(repository.findValue("signup.code")).contains("");
	}

	@Test
	void 없는_키는_empty다() {
		assertThat(repository.findValue("no.such.key")).isEmpty();
	}

	@Test
	void UPDATE_후_재조회는_새_값을_돌려준다() {
		jdbcClient.sql("UPDATE app.app_setting SET value = 'BETA2026' WHERE key = 'signup.code'").update();

		assertThat(repository.findValue("signup.code")).contains("BETA2026");

		// 다른 테스트가 시드 상태를 전제하므로 원복
		jdbcClient.sql("UPDATE app.app_setting SET value = '' WHERE key = 'signup.code'").update();
	}
}
