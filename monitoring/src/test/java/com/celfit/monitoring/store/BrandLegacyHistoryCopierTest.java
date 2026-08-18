package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.testsupport.TestDb;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 레거시(캠페인) 이력 → 브랜드 전용 테이블 복사(2026-08-18 direct 통합 §4-3, import 모드) —
 * BrandStoreTest와 같은 Testcontainers 관용구. 컬럼 동형성은 BrandLegacyHistoryCopier 클래스
 * javadoc의 대조 결과(설계 R9)를 실제 DB 왕복으로 확인한다.
 */
class BrandLegacyHistoryCopierTest {

	private JdbcTemplate db;
	private BrandLegacyHistoryCopier copier;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		copier = new BrandLegacyHistoryCopier(db);
	}

	@Test
	void 레거시_스냅샷_3행이_있는_shortcode를_복사한다() {
		insertLegacySnapshot("Legacy1", LocalDate.of(2026, 7, 1));
		insertLegacySnapshot("Legacy1", LocalDate.of(2026, 7, 2));
		insertLegacySnapshot("Legacy1", LocalDate.of(2026, 7, 3));
		insertLegacyMeta("Legacy1");
		insertLegacyComment("Legacy1", "c1");

		copier.copy("Legacy1");

		assertThat(db.queryForObject(
				"SELECT count(*) FROM brand_post_snapshot WHERE short_code='Legacy1'", Long.class))
				.isEqualTo(3L);
		assertThat(db.queryForObject(
				"SELECT count(*) FROM brand_post_meta WHERE short_code='Legacy1'", Long.class))
				.isEqualTo(1L);
		assertThat(db.queryForObject(
				"SELECT count(*) FROM brand_post_comment WHERE short_code='Legacy1'", Long.class))
				.isEqualTo(1L);
	}

	@Test
	void 지표_컬럼이_그대로_복사된다() {
		db.update("""
				INSERT INTO post_snapshot (username, short_code, captured_on, content_type, likes,
				                           likes_hidden, comments, views, fb_plays, saves, shares,
				                           shares_hidden, reposts)
				VALUES ('creator', 'Legacy2', '2026-07-01', 'REELS', 100, false, 5, 1000, 20, 7, 3, false, 2)""");

		copier.copy("Legacy2");

		assertThat(db.queryForObject(
				"SELECT likes FROM brand_post_snapshot WHERE short_code='Legacy2'", Long.class)).isEqualTo(100L);
		assertThat(db.queryForObject(
				"SELECT views FROM brand_post_snapshot WHERE short_code='Legacy2'", Long.class)).isEqualTo(1000L);
		assertThat(db.queryForObject(
				"SELECT fb_plays FROM brand_post_snapshot WHERE short_code='Legacy2'", Long.class)).isEqualTo(20L);
		assertThat(db.queryForObject(
				"SELECT saves FROM brand_post_snapshot WHERE short_code='Legacy2'", Long.class)).isEqualTo(7L);
	}

	@Test
	void 메타_복사는_브랜드_전용_3컬럼을_NULL로_둔다() {
		insertLegacyMeta("Legacy3");

		copier.copy("Legacy3");

		assertThat(db.queryForObject(
				"SELECT video_url FROM brand_post_meta WHERE short_code='Legacy3'", String.class)).isNull();
		assertThat(db.queryForObject(
				"SELECT video_duration FROM brand_post_meta WHERE short_code='Legacy3'", Double.class)).isNull();
		assertThat(db.queryForObject(
				"SELECT is_paid_partnership FROM brand_post_meta WHERE short_code='Legacy3'", Boolean.class))
				.isNull();
		assertThat(db.queryForObject(
				"SELECT caption FROM brand_post_meta WHERE short_code='Legacy3'", String.class))
				.isEqualTo("레거시 캡션");
	}

	@Test
	void 재실행이_중복_행을_만들지_않는다() {
		insertLegacySnapshot("Legacy4", LocalDate.of(2026, 7, 1));
		insertLegacyMeta("Legacy4");
		insertLegacyComment("Legacy4", "c1");

		copier.copy("Legacy4");
		copier.copy("Legacy4");   // 재실행 — ON CONFLICT DO NOTHING

		assertThat(db.queryForObject(
				"SELECT count(*) FROM brand_post_snapshot WHERE short_code='Legacy4'", Long.class))
				.isEqualTo(1L);
		assertThat(db.queryForObject(
				"SELECT count(*) FROM brand_post_meta WHERE short_code='Legacy4'", Long.class))
				.isEqualTo(1L);
		assertThat(db.queryForObject(
				"SELECT count(*) FROM brand_post_comment WHERE short_code='Legacy4'", Long.class))
				.isEqualTo(1L);
	}

	@Test
	void 복사_후에도_레거시_원본은_그대로_남는다() {
		insertLegacySnapshot("Legacy5", LocalDate.of(2026, 7, 1));
		insertLegacyMeta("Legacy5");
		insertLegacyComment("Legacy5", "c1");

		copier.copy("Legacy5");

		assertThat(db.queryForObject(
				"SELECT count(*) FROM post_snapshot WHERE short_code='Legacy5'", Long.class)).isEqualTo(1L);
		assertThat(db.queryForObject(
				"SELECT count(*) FROM post_meta WHERE short_code='Legacy5'", Long.class)).isEqualTo(1L);
		assertThat(db.queryForObject(
				"SELECT count(*) FROM post_comment WHERE short_code='Legacy5'", Long.class)).isEqualTo(1L);
	}

	private void insertLegacySnapshot(String shortCode, LocalDate capturedOn) {
		db.update("""
				INSERT INTO post_snapshot (username, short_code, captured_on, content_type, likes,
				                           likes_hidden, comments, views, fb_plays, saves, shares,
				                           shares_hidden, reposts)
				VALUES ('creator', ?, ?, 'REELS', 10, false, 2, 100, 0, null, null, false, null)""",
				shortCode, capturedOn);
	}

	private void insertLegacyMeta(String shortCode) {
		db.update("""
				INSERT INTO post_meta (short_code, username, content_type, uploaded_at, caption, thumbnail_url)
				VALUES (?, 'creator', 'REELS', '2026-07-01', '레거시 캡션', 'https://thumb')""",
				shortCode);
	}

	private void insertLegacyComment(String shortCode, String id) {
		db.update("""
				INSERT INTO post_comment (short_code, id, author, body, like_count, commented_at)
				VALUES (?, ?, 'fan', '댓글', 1, now())""",
				shortCode, id);
	}
}
