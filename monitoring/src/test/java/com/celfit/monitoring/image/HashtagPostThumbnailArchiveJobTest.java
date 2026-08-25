package com.celfit.monitoring.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.testsupport.CdnUrls;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 해시태그 게시물 썸네일 아카이브 잡 계약({@link BrandPostThumbnailArchiveJobTest}과 동형) + 고유 계약 둘:
 * ① RELEVANT만 후보다(비노출 판정은 아카이브하지 않는다 — 판정은 저장 후 불변)
 * ② (brand_id, short_code) PK라 같은 게시물이 브랜드별 행으로 중복될 수 있다 — 다운로드는 1회,
 *   UPDATE는 short_code 기준 전 브랜드 행.
 * ③ 만료(oe) URL은 시도 없이 제외한다(08-25 배치 상한 완전 제거 — 상한 관련 계약은 폐기) — 그래서
 *   만료가 무관한 픽스처의 oe는 CdnUrls.farFutureOe()(실행 시점 +10년)로 만든다.
 */
class HashtagPostThumbnailArchiveJobTest {

	JdbcTemplate db;
	List<String> downloads = new ArrayList<>();
	List<Map<String, String>> puts = new ArrayList<>();
	List<String> failUrls = new ArrayList<>();

	ImageDownloader fakeDownloader() {
		return url -> {
			downloads.add(url);
			if (failUrls.contains(url)) {
				throw new IllegalStateException("다운로드 실패 HTTP 403: " + url);
			}
			return new ImageDownloader.Downloaded("bytes".getBytes(), "image/jpeg");
		};
	}

	ImageStore fakeStore() {
		return (objectPath, bytes, contentType, cacheControl) ->
				puts.add(Map.of("path", objectPath, "cacheControl", cacheControl));
	}

	HashtagPostThumbnailArchiveJob job(String parUrl) {
		return new HashtagPostThumbnailArchiveJob(db, fakeStore(), fakeDownloader(), parUrl);
	}

	HashtagPostThumbnailArchiveJob job() {
		return job("https://par.example/o/");
	}

	@BeforeEach
	void setUp() {
		var pg = TestDb.container();
		var ds = TestDb.dataSource(pg);
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		db.update("TRUNCATE brand_hashtag_post, brand_hashtag, brand_hashtag_exclusion, brand_account CASCADE");
		downloads.clear();
		puts.clear();
		failUrls.clear();
	}

	long seedBrand(String username) {
		return db.queryForObject("""
				INSERT INTO brand_account (username, ig_user_id, status)
				VALUES (?, ?, 'ACTIVE') RETURNING id
				""", Long.class, username, String.valueOf(username.hashCode()));
	}

	void seedPost(long brandId, String shortCode, String verdict, String thumbnailUrl,
			String imageObjectPath, String imageSourceName) {
		db.update("""
				INSERT INTO brand_hashtag_post (brand_id, short_code, matched_tag, author_username, taken_at,
				                                caption, thumbnail_url, verdict, verdict_source,
				                                image_object_path, image_source_name)
				VALUES (?, ?, '#tag', 'author_a', ?, '', ?, ?, 'RULE', ?, ?)
				""", brandId, shortCode, OffsetDateTime.parse("2026-08-10T00:00:00Z"), thumbnailUrl,
				verdict, imageObjectPath, imageSourceName);
	}

	@Test
	void RELEVANT_게시물만_아카이브한다() {
		long brand = seedBrand("brand_a");
		seedPost(brand, "REL", "RELEVANT", "https://cdn.example/rel_n.jpg", null, null);
		seedPost(brand, "UNC", "UNCERTAIN", "https://cdn.example/unc_n.jpg", null, null);
		seedPost(brand, "IRR", "IRRELEVANT", "https://cdn.example/irr_n.jpg", null, null);

		job().run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-hashtag-post/REL.jpg");
		var row = db.queryForMap("""
				SELECT image_object_path, image_source_name, image_archived_at
				FROM brand_hashtag_post WHERE short_code='REL'
				""");
		assertThat(row.get("image_object_path")).isEqualTo("monitor-hashtag-post/REL.jpg");
		assertThat(row.get("image_source_name")).isEqualTo("rel_n.jpg");
		assertThat(row.get("image_archived_at")).isNotNull();
	}

	/** 같은 게시물이 두 브랜드 행으로 중복돼도 다운로드는 1회, 경로는 전 브랜드 행에 채워진다. */
	@Test
	void 브랜드별_중복_행은_한_번만_다운로드하고_전_행에_경로를_채운다() {
		long brandA = seedBrand("brand_a");
		long brandB = seedBrand("brand_b");
		seedPost(brandA, "SC1", "RELEVANT", "https://cdn.example/one_n.jpg?" + CdnUrls.farFutureOe() + "&cb=a", null, null);
		seedPost(brandB, "SC1", "RELEVANT", "https://cdn.example/one_n.jpg?" + CdnUrls.farFutureOe() + "&cb=b", null, null);

		job().run();

		assertThat(downloads).hasSize(1);
		Long filled = db.queryForObject(
				"SELECT count(image_object_path) FROM brand_hashtag_post WHERE short_code='SC1'", Long.class);
		assertThat(filled).isEqualTo(2);
	}

	@Test
	void 쿼리스트링만_다르고_파일명이_같으면_스킵한다() {
		long brand = seedBrand("brand_a");
		seedPost(brand, "SC1", "RELEVANT", "https://cdn-b.example/v/999_n.jpg?" + CdnUrls.farFutureOe(),
				"monitor-hashtag-post/SC1.jpg", "999_n.jpg");

		job().run();

		assertThat(downloads).isEmpty();
		assertThat(puts).isEmpty();
	}

	@Test
	void 한_건_다운로드_실패가_나머지_게시물을_막지_않는다() {
		long brand = seedBrand("brand_a");
		seedPost(brand, "BAD", "RELEVANT", "https://cdn.example/expired_n.jpg", null, null);
		seedPost(brand, "GOOD", "RELEVANT", "https://cdn.example/ok_n.jpg", null, null);
		failUrls.add("https://cdn.example/expired_n.jpg");

		job().run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-hashtag-post/GOOD.jpg");
	}

	@Test
	void PAR_미설정이면_no_op이다() {
		long brand = seedBrand("brand_a");
		seedPost(brand, "SC1", "RELEVANT", "https://cdn.example/1_n.jpg", null, null);

		job("").run();

		assertThat(downloads).isEmpty();
	}

	/** 대량 백로그도 한 스윕에서 전량 처리된다(08-25 배치 상한 완전 제거 — 상한 관련 계약은 폐기). */
	@Test
	void 미아카이브_행_전량이_한_스윕에서_처리된다() {
		long brand = seedBrand("brand_a");
		seedPost(brand, "DONE1", "RELEVANT", "https://cdn.example/a_n.jpg",
				"monitor-hashtag-post/DONE1.jpg", "a_n.jpg");
		seedPost(brand, "NEW1", "RELEVANT", "https://cdn.example/d_n.jpg", null, null);
		seedPost(brand, "NEW2", "RELEVANT", "https://cdn.example/e_n.jpg", null, null);

		job().run();

		assertThat(puts).hasSize(2);
		Long archived = db.queryForObject(
				"SELECT count(image_object_path) FROM brand_hashtag_post WHERE short_code LIKE 'NEW%'", Long.class);
		assertThat(archived).isEqualTo(2);
	}

	/** 만료 URL은 재시도해도 영원히 403 — 시도 자체를 걸러 예산(HTTP 왕복)을 낭비하지 않는다. */
	@Test
	void 만료된_URL은_다운로드_시도조차_하지_않는다() {
		long brand = seedBrand("brand_a");
		seedPost(brand, "DEAD", "RELEVANT", CdnUrls.expiringIn("dead_n.jpg", -3600), null, null);
		seedPost(brand, "LIVE", "RELEVANT", CdnUrls.expiringIn("live_n.jpg", 86400), null, null);
		seedPost(brand, "UNKNOWN", "RELEVANT", CdnUrls.noOe("unknown_n.jpg"), null, null);

		job().run();

		assertThat(downloads).noneMatch(u -> u.contains("dead_n.jpg"));
		assertThat(puts).extracting(m -> m.get("path"))
				.containsExactlyInAnyOrder("monitor-hashtag-post/LIVE.jpg", "monitor-hashtag-post/UNKNOWN.jpg");
	}
}
