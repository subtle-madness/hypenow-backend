package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.testsupport.TestDb;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AppSettingRepositoryTest {

	JdbcTemplate db;
	AppSettingRepository settings;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		settings = new AppSettingRepository(db);
	}

	/** 마이그레이션 시드값이 그대로 조회된다 — 개통 전 전량 off 기준값. */
	@Test
	void 시드된_토글_기준값을_조회한다() {
		assertThat(settings.find("ig-source.self-enabled")).isEqualTo(Optional.of("false"));
		assertThat(settings.find("ig-source.force-hiker")).isEqualTo(Optional.of("false"));
		assertThat(settings.find("ig-source.profile-surface")).isEqualTo(Optional.of("wpi"));
	}

	@Test
	void 없는_키는_empty다() {
		assertThat(settings.find("nonexistent")).isEmpty();
	}

	@Test
	void upsert는_기존_키를_덮는다() {
		settings.upsert("ig-source.self-enabled", "true");
		assertThat(settings.find("ig-source.self-enabled")).isEqualTo(Optional.of("true"));
	}

	@Test
	void upsert는_새_키를_생성한다() {
		settings.upsert("new.key", "v");
		assertThat(settings.find("new.key")).isEqualTo(Optional.of("v"));
	}
}
