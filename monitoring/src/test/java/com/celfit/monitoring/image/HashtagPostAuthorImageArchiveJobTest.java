package com.celfit.monitoring.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.testsupport.TestDb;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 해시태그 게시물 작성자 이미지 아카이브 잡 계약({@link HashtagPostThumbnailArchiveJobTest}과 동형) +
 * 고유 계약 둘:
 * ① RELEVANT만 후보다(비노출 판정은 아카이브하지 않는다)
 * ② author_username 단위로 dedupe한다 — 같은 작성자가 여러 행(여러 게시물)에 걸쳐 있어도 다운로드는
 *   1회, UPDATE는 그 작성자의 미아카이브 행 전부. 후보 자체가 미아카이브(author_image_object_path
 *   IS NULL) 행으로 좁혀지므로 "파일명 불변 스킵" 케이스는 없다(썸네일 잡과의 차이).
 */
class HashtagPostAuthorImageArchiveJobTest {

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

	HashtagPostAuthorImageArchiveJob job(String parUrl, int batchLimit) {
		return new HashtagPostAuthorImageArchiveJob(db, fakeStore(), fakeDownloader(), parUrl, batchLimit);
	}

	HashtagPostAuthorImageArchiveJob job() {
		return job("https://par.example/o/", 1000);
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

	void seedPost(long brandId, String shortCode, String verdict, String authorUsername,
			String authorProfilePicUrl, String authorImageObjectPath, String authorImageSourceName) {
		db.update("""
				INSERT INTO brand_hashtag_post (brand_id, short_code, matched_tag, author_username,
				                                author_profile_pic_url, taken_at, caption, verdict,
				                                verdict_source, author_image_object_path, author_image_source_name)
				VALUES (?, ?, '#tag', ?, ?, ?, '', ?, 'RULE', ?, ?)
				""", brandId, shortCode, authorUsername, authorProfilePicUrl,
				OffsetDateTime.parse("2026-08-10T00:00:00Z"), verdict, authorImageObjectPath, authorImageSourceName);
	}

	@Test
	void RELEVANT_게시물_작성자만_아카이브한다() {
		long brand = seedBrand("brand_a");
		seedPost(brand, "REL", "RELEVANT", "author_rel", "https://cdn.example/rel_n.jpg", null, null);
		seedPost(brand, "UNC", "UNCERTAIN", "author_unc", "https://cdn.example/unc_n.jpg", null, null);
		seedPost(brand, "IRR", "IRRELEVANT", "author_irr", "https://cdn.example/irr_n.jpg", null, null);

		job().run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-hashtag-author/author_rel.jpg");
		var row = db.queryForMap("""
				SELECT author_image_object_path, author_image_source_name, author_image_archived_at
				FROM brand_hashtag_post WHERE short_code='REL'
				""");
		assertThat(row.get("author_image_object_path")).isEqualTo("monitor-hashtag-author/author_rel.jpg");
		assertThat(row.get("author_image_source_name")).isEqualTo("rel_n.jpg");
		assertThat(row.get("author_image_archived_at")).isNotNull();
	}

	/** 같은 작성자가 여러 게시물 행으로 등장해도 다운로드는 1회, 경로는 그 작성자의 미아카이브 행 전부에 채워진다. */
	@Test
	void 같은_작성자의_여러_행은_한_번만_다운로드하고_전_행에_경로를_채운다() {
		long brand = seedBrand("brand_a");
		seedPost(brand, "SC1", "RELEVANT", "author_a", "https://cdn.example/one_n.jpg?oe=a", null, null);
		seedPost(brand, "SC2", "RELEVANT", "author_a", "https://cdn.example/one_n.jpg?oe=b", null, null);

		job().run();

		assertThat(downloads).hasSize(1);
		Long filled = db.queryForObject(
				"SELECT count(author_image_object_path) FROM brand_hashtag_post WHERE author_username='author_a'",
				Long.class);
		assertThat(filled).isEqualTo(2);
	}

	/** 이미 아카이브된(author_image_object_path NOT NULL) 행은 애초에 후보에 들지 않는다. */
	@Test
	void 이미_아카이브된_작성자는_후보에서_제외된다() {
		long brand = seedBrand("brand_a");
		seedPost(brand, "SC1", "RELEVANT", "author_done", "https://cdn-b.example/v/999_n.jpg?oe=new",
				"monitor-hashtag-author/author_done.jpg", "999_n.jpg");

		job().run();

		assertThat(downloads).isEmpty();
		assertThat(puts).isEmpty();
	}

	@Test
	void 한_작성자_다운로드_실패가_나머지_작성자를_막지_않는다() {
		long brand = seedBrand("brand_a");
		seedPost(brand, "BAD", "RELEVANT", "author_bad", "https://cdn.example/expired_n.jpg", null, null);
		seedPost(brand, "GOOD", "RELEVANT", "author_good", "https://cdn.example/ok_n.jpg", null, null);
		failUrls.add("https://cdn.example/expired_n.jpg");

		job().run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-hashtag-author/author_good.jpg");
	}

	@Test
	void PAR_미설정이면_no_op이다() {
		long brand = seedBrand("brand_a");
		seedPost(brand, "SC1", "RELEVANT", "author_a", "https://cdn.example/1_n.jpg", null, null);

		job("", 1000).run();

		assertThat(downloads).isEmpty();
	}

	/** 상한은 다운로드 시도만 소모한다 — 근거는 BrandPostThumbnailArchiveJobTest의 동명 테스트 참고. */
	@Test
	void 배치_상한은_다운로드_시도만_소모한다() {
		long brand = seedBrand("brand_a");
		seedPost(brand, "NEW1", "RELEVANT", "author_1", "https://cdn.example/d_n.jpg", null, null);
		seedPost(brand, "NEW2", "RELEVANT", "author_2", "https://cdn.example/e_n.jpg", null, null);

		job("https://par.example/o/", 1).run();

		assertThat(puts).hasSize(1);
		Long archived = db.queryForObject(
				"SELECT count(*) FROM brand_hashtag_post WHERE author_image_object_path IS NOT NULL", Long.class);
		assertThat(archived).isEqualTo(1);
	}
}
