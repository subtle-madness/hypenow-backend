package com.celfit.monitoring.hiker;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.store.AppSettingRepository;
import com.celfit.monitoring.testsupport.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 자체크롤 런타임 토글·킬스위치 판정 — app_setting 시드 기준값(전량 off)과 재시작 없는 반영 검증. */
class IgSourceSettingsTest {

	JdbcTemplate db;
	AppSettingRepository repo;
	IgSourceSettings settings;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		repo = new AppSettingRepository(db);
		settings = new IgSourceSettings(repo);
	}

	/** 마이그레이션 시드 기준값(self-enabled=false) — 개통 전 행동 변화 0. */
	@Test
	void 시드_기본값에서_selfEnabled는_false다() {
		assertThat(settings.selfEnabled()).isFalse();
	}

	@Test
	void self_enabled를_true로_올리면_재시작_없이_selfEnabled가_true다() {
		repo.upsert("ig-source.self-enabled", "true");
		assertThat(settings.selfEnabled()).isTrue();
	}

	@Test
	void 킬스위치_force_hiker가_true면_self_enabled가_true라도_false다() {
		repo.upsert("ig-source.self-enabled", "true");
		repo.upsert("ig-source.force-hiker", "true");
		assertThat(settings.selfEnabled()).isFalse();
	}

	@Test
	void profileSurface_기본은_wpi고_upsert로_og_전환된다() {
		assertThat(settings.profileSurface()).isEqualTo("wpi");
		repo.upsert("ig-source.profile-surface", "og");
		assertThat(settings.profileSurface()).isEqualTo("og");
	}
}
