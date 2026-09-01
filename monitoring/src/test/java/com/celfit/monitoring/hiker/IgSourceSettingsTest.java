package com.celfit.monitoring.hiker;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.store.AppSettingRepository;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Duration;
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
		InstagramProxyProperties proxyProps = new InstagramProxyProperties(
				null, null, Duration.ofSeconds(15), false, "ENV_DOC_ID", "ENV_FRIENDLY_NAME");
		settings = new IgSourceSettings(repo, proxyProps);
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

	/** 마이그레이션 시드값(V20260901~ig_source_comment_doc_id) — env 폴백보다 app_setting이 우선. */
	@Test
	void commentDocId_는_마이그레이션_시드값이다() {
		assertThat(settings.commentDocId()).isEqualTo("27659279553772821");
		assertThat(settings.commentFriendlyName())
				.isEqualTo("PolarisLoggedOutDesktopWWWPostCommentsPaginationQuery");
	}

	@Test
	void commentDocId_upsert하면_재시작_없이_반영된다() {
		repo.upsert("ig-source.comment-doc-id", "NEW_DOC_ID");
		repo.upsert("ig-source.comment-friendly-name", "NewFriendlyName");
		assertThat(settings.commentDocId()).isEqualTo("NEW_DOC_ID");
		assertThat(settings.commentFriendlyName()).isEqualTo("NewFriendlyName");
	}

	@Test
	void commentDocId_app_setting이_비어있으면_env_폴백() {
		db.update("DELETE FROM app_setting WHERE key IN "
				+ "('ig-source.comment-doc-id', 'ig-source.comment-friendly-name')");
		assertThat(settings.commentDocId()).isEqualTo("ENV_DOC_ID");
		assertThat(settings.commentFriendlyName()).isEqualTo("ENV_FRIENDLY_NAME");
	}
}
