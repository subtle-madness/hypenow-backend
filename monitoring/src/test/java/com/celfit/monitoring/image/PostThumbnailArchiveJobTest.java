package com.celfit.monitoring.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.testsupport.TestDb;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 게시물 썸네일 아카이브 잡 계약({@link ProfileImageArchiveJobTest}과 동형, 트랙 KK 확장):
 * ① 신규 아카이브(object_path·source_name·archived_at 기록) ② source_name 미변경 시 재다운로드 스킵
 * ③ 쿼리스트링만 다르고 파일명이 같으면 스킵(핵심 회귀 방지 — 매일 전량 재다운로드 버그)
 * ④ 한 건 실패 격리(계속 진행) ⑤ PAR 미설정 시 no-op ⑥ http(s) 아닌/null thumbnail_url은 후보 제외.
 */
class PostThumbnailArchiveJobTest {

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

	PostThumbnailArchiveJob job(String parUrl, int batchLimit) {
		return new PostThumbnailArchiveJob(db, fakeStore(), fakeDownloader(), parUrl, batchLimit);
	}

	PostThumbnailArchiveJob job() {
		return job("https://par.example/o/", 1000);
	}

	@BeforeEach
	void setUp() {
		var pg = TestDb.container();
		var ds = TestDb.dataSource(pg);
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		db.update("TRUNCATE post_meta");
		downloads.clear();
		puts.clear();
		failUrls.clear();
	}

	void seedPostMeta(String shortCode, String thumbnailUrl, String imageObjectPath, String imageSourceName) {
		db.update("""
				INSERT INTO post_meta (short_code, username, uploaded_at, caption, thumbnail_url, image_object_path, image_source_name)
				VALUES (?, 'acct_a', ?, '', ?, ?, ?)
				""", shortCode, LocalDate.of(2026, 7, 28), thumbnailUrl, imageObjectPath, imageSourceName);
	}

	@Test
	void 신규_아카이브는_object_path_source_name_archived_at을_기록한다() {
		seedPostMeta("SC1", "https://cdn.example/v/t51/463_111_n.jpg?oe=abc", null, null);

		job().run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-post/SC1.jpg");
		assertThat(puts.get(0).get("cacheControl")).isEqualTo("public, max-age=86400");
		var row = db.queryForMap("""
				SELECT image_object_path, image_source_name, image_archived_at FROM post_meta WHERE short_code='SC1'
				""");
		assertThat(row.get("image_object_path")).isEqualTo("monitor-post/SC1.jpg");
		assertThat(row.get("image_source_name")).isEqualTo("463_111_n.jpg");
		assertThat(row.get("image_archived_at")).isNotNull();
	}

	@Test
	void source_name이_바뀌지_않으면_재다운로드를_스킵한다() {
		seedPostMeta("SC1", "https://cdn.example/v/463_111_n.jpg?oe=abc",
				"monitor-post/SC1.jpg", "463_111_n.jpg");

		job().run();

		assertThat(downloads).isEmpty();
		assertThat(puts).isEmpty();
	}

	/** 핵심 회귀 방지 — 인스타 CDN은 oe=(서명) 쿼리파라미터가 매 조회마다 바뀐다. 파일명만 비교해야 한다. */
	@Test
	void 쿼리스트링만_다르고_파일명이_같으면_스킵한다() {
		seedPostMeta("SC1", "https://cdn-a.example/v/999_222_n.jpg?oe=old&sig=1",
				"monitor-post/SC1.jpg", "999_222_n.jpg");
		// 호스트·쿼리만 바뀐 같은 파일명
		db.update("UPDATE post_meta SET thumbnail_url = ? WHERE short_code = 'SC1'",
				"https://cdn-b.example/v/999_222_n.jpg?oe=new&sig=99");

		job().run();

		assertThat(downloads).isEmpty();
		assertThat(puts).isEmpty();
	}

	@Test
	void 파일명이_바뀌면_같은_키로_재업로드하고_source_name을_갱신한다() {
		seedPostMeta("SC1", "https://cdn.example/v/999_222_n.jpg?oe=old",
				"monitor-post/SC1.jpg", "999_222_n.jpg");
		db.update("UPDATE post_meta SET thumbnail_url = ? WHERE short_code = 'SC1'",
				"https://cdn.example/v/1000_333_n.jpg?oe=new");

		job().run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-post/SC1.jpg");
		String sourceName = db.queryForObject(
				"SELECT image_source_name FROM post_meta WHERE short_code='SC1'", String.class);
		assertThat(sourceName).isEqualTo("1000_333_n.jpg");
	}

	@Test
	void 한_건_다운로드_실패가_나머지_게시물을_막지_않는다() {
		seedPostMeta("BAD", "https://cdn.example/expired_n.jpg", null, null);
		seedPostMeta("GOOD", "https://cdn.example/ok_n.jpg", null, null);
		failUrls.add("https://cdn.example/expired_n.jpg");

		job().run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-post/GOOD.jpg");
		String badPath = db.queryForObject(
				"SELECT image_object_path FROM post_meta WHERE short_code='BAD'", String.class);
		assertThat(badPath).isNull();   // 실패분은 기록되지 않아 다음 실행에서 재대상
	}

	@Test
	void PAR_미설정이면_no_op이다() {
		seedPostMeta("SC1", "https://cdn.example/1_n.jpg", null, null);

		job("", 1000).run();

		assertThat(downloads).isEmpty();
		assertThat(puts).isEmpty();
	}

	@Test
	void null이거나_http로_시작하지_않는_thumbnail_url은_후보에서_제외된다() {
		seedPostMeta("NO_URL", null, null, null);
		seedPostMeta("INVALID", "exception://", null, null);
		seedPostMeta("VALID", "https://cdn.example/ok_n.jpg", null, null);

		job().run();

		assertThat(downloads).containsExactly("https://cdn.example/ok_n.jpg");
		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-post/VALID.jpg");
	}
}
