package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.BrandPostMetaRepository;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandSnapshotRepository;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 브랜드 쓰기 경계의 <b>배선</b> 검증(리뷰 I2 — SnapshotWriterAlarmTest 동형) — 스토어 단위
 * 테스트(BrandStoreTest)가 SQL은 덮지만, writer가 실제로 어떤 인자를 어떤 순서로 넘기는지는
 * 이 경로에서만 드러난다. 표시 메타는 인접 필드가 같은 타입(thumbnailUrl·videoUrl 둘 다 String)
 * 이라 뒤바뀌어도 컴파일된다 — 그래서 값을 서로 다르게 넣고 컬럼별로 직조회한다.
 */
class BrandSnapshotWriterTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final long TAKEN_AT = 1_785_000_000L;

	private JdbcTemplate db;
	private BrandRepository brands;
	private BrandSnapshotWriter writer;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		brands = new BrandRepository(db);
		writer = new BrandSnapshotWriter(new BrandSnapshotRepository(db),
				new BrandPostMetaRepository(db), brands);
	}

	@Test
	void savePost는_brand_post_meta에_표시_메타를_적재한다() {
		writer.savePost(LocalDate.of(2026, 8, 7), post());

		var row = db.queryForMap("SELECT * FROM brand_post_meta WHERE short_code='CodeA'");
		assertThat(row.get("username")).isEqualTo("creator");
		assertThat(row.get("content_type")).isEqualTo("REELS");
		assertThat(row.get("caption")).isEqualTo("캡션");
		// 썸네일과 영상 URL이 각자 컬럼에 들어간다(인자 뒤바뀜 검출 — 둘 다 String).
		assertThat(row.get("thumbnail_url")).isEqualTo("https://cdn/thumb.jpg");
		assertThat(row.get("video_url")).isEqualTo("https://cdn/video.mp4");
		assertThat(row.get("video_duration")).isEqualTo(12.5);
		assertThat(row.get("is_paid_partnership")).isEqualTo(true);
		assertThat(row.get("uploaded_at")).isEqualTo(java.sql.Date.valueOf(
				Instant.ofEpochSecond(TAKEN_AT).atZone(KST).toLocalDate()));
		// 스냅샷도 같은 트랜잭션에서 함께 적재된다.
		assertThat(db.queryForObject(
				"SELECT count(*) FROM brand_post_snapshot WHERE short_code='CodeA'", Long.class))
				.isEqualTo(1L);
	}

	@Test
	void saveBrandProfile은_최신값과_추이를_함께_적재한다() {
		long id = brands.insertOrReactivate("brandx",
				new ProfileInfo("brandx", "111", 1L, null, null, null, null, null, null, null));

		writer.saveBrandProfile(id, "brandx", LocalDate.of(2026, 8, 7),
				new ProfileInfo("brandx", "111", 1000L, 10L, 5L, "브랜드", "https://cdn/pic.jpg",
						"소개", true, "https://brand.example"));

		var row = db.queryForMap("SELECT * FROM brand_account WHERE id = " + id);
		assertThat(row.get("followers")).isEqualTo(1000L);
		assertThat(row.get("following")).isEqualTo(10L);
		assertThat(row.get("media_count")).isEqualTo(5L);
		assertThat(row.get("full_name")).isEqualTo("브랜드");
		// 프로필 사진과 외부 링크도 각자 컬럼에(둘 다 String — 순서 뒤바뀜 검출).
		assertThat(row.get("profile_pic_url")).isEqualTo("https://cdn/pic.jpg");
		assertThat(row.get("external_url")).isEqualTo("https://brand.example");
		assertThat(row.get("biography")).isEqualTo("소개");
		assertThat(row.get("is_verified")).isEqualTo(true);
		assertThat(db.queryForObject("""
				SELECT followers FROM brand_profile_snapshot
				WHERE username='brandx' AND captured_on='2026-08-07'""", Long.class)).isEqualTo(1000L);
	}

	private static PostInfo post() {
		return new PostInfo("CodeA", "creator", null, null, "999", "REELS", "캡션",
				"https://cdn/thumb.jpg", TAKEN_AT, 100L, 5L, 1000L, null, 20L, 3L, 1L,
				"https://cdn/video.mp4", 12.5, true, true, false, false);
	}
}
