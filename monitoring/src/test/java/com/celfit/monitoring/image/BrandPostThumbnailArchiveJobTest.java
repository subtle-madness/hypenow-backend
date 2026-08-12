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
 * 브랜드 게시물 썸네일 아카이브 잡 계약({@link PostThumbnailArchiveJobTest}과 동형):
 * ① 신규 아카이브 ② source_name 미변경 시 스킵 ③ 쿼리스트링만 다르면 스킵 ④ 한 건 실패 격리
 * ⑤ PAR 미설정 no-op ⑥ 무효 thumbnail_url 후보 제외
 * + ⑦ 배치 상한은 다운로드 시도만 소모한다(스킵 공짜 — 기존 잡의 창 잠식 결함을 반복하지 않는 핵심 계약).
 */
class BrandPostThumbnailArchiveJobTest {

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

	BrandPostThumbnailArchiveJob job(String parUrl, int batchLimit) {
		return new BrandPostThumbnailArchiveJob(db, fakeStore(), fakeDownloader(), parUrl, batchLimit);
	}

	BrandPostThumbnailArchiveJob job() {
		return job("https://par.example/o/", 1000);
	}

	@BeforeEach
	void setUp() {
		var pg = TestDb.container();
		var ds = TestDb.dataSource(pg);
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		db.update("TRUNCATE brand_post_meta");
		downloads.clear();
		puts.clear();
		failUrls.clear();
	}

	void seed(String shortCode, String thumbnailUrl, String imageObjectPath, String imageSourceName) {
		db.update("""
				INSERT INTO brand_post_meta (short_code, username, uploaded_at, caption, thumbnail_url,
				                             image_object_path, image_source_name)
				VALUES (?, 'acct_a', ?, '', ?, ?, ?)
				""", shortCode, LocalDate.of(2026, 8, 10), thumbnailUrl, imageObjectPath, imageSourceName);
	}

	@Test
	void 신규_아카이브는_object_path_source_name_archived_at을_기록한다() {
		seed("SC1", "https://cdn.example/v/t51/463_111_n.jpg?oe=abc", null, null);

		job().run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-brand-post/SC1.jpg");
		assertThat(puts.get(0).get("cacheControl")).isEqualTo("public, max-age=86400");
		var row = db.queryForMap("""
				SELECT image_object_path, image_source_name, image_archived_at FROM brand_post_meta WHERE short_code='SC1'
				""");
		assertThat(row.get("image_object_path")).isEqualTo("monitor-brand-post/SC1.jpg");
		assertThat(row.get("image_source_name")).isEqualTo("463_111_n.jpg");
		assertThat(row.get("image_archived_at")).isNotNull();
	}

	/** 핵심 회귀 방지 — 인스타 CDN은 oe=(서명) 쿼리파라미터가 매 조회마다 바뀐다. 파일명만 비교해야 한다. */
	@Test
	void 쿼리스트링만_다르고_파일명이_같으면_스킵한다() {
		seed("SC1", "https://cdn-a.example/v/999_222_n.jpg?oe=old&sig=1",
				"monitor-brand-post/SC1.jpg", "999_222_n.jpg");
		db.update("UPDATE brand_post_meta SET thumbnail_url = ? WHERE short_code = 'SC1'",
				"https://cdn-b.example/v/999_222_n.jpg?oe=new&sig=99");

		job().run();

		assertThat(downloads).isEmpty();
		assertThat(puts).isEmpty();
	}

	@Test
	void 파일명이_바뀌면_같은_키로_재업로드하고_source_name을_갱신한다() {
		seed("SC1", "https://cdn.example/v/999_222_n.jpg?oe=old",
				"monitor-brand-post/SC1.jpg", "999_222_n.jpg");
		db.update("UPDATE brand_post_meta SET thumbnail_url = ? WHERE short_code = 'SC1'",
				"https://cdn.example/v/1000_333_n.jpg?oe=new");

		job().run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-brand-post/SC1.jpg");
		String sourceName = db.queryForObject(
				"SELECT image_source_name FROM brand_post_meta WHERE short_code='SC1'", String.class);
		assertThat(sourceName).isEqualTo("1000_333_n.jpg");
	}

	@Test
	void 한_건_다운로드_실패가_나머지_게시물을_막지_않는다() {
		seed("BAD", "https://cdn.example/expired_n.jpg", null, null);
		seed("GOOD", "https://cdn.example/ok_n.jpg", null, null);
		failUrls.add("https://cdn.example/expired_n.jpg");

		job().run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-brand-post/GOOD.jpg");
		String badPath = db.queryForObject(
				"SELECT image_object_path FROM brand_post_meta WHERE short_code='BAD'", String.class);
		assertThat(badPath).isNull();   // 실패분은 기록되지 않아 다음 실행에서 재대상
	}

	@Test
	void PAR_미설정이면_no_op이다() {
		seed("SC1", "https://cdn.example/1_n.jpg", null, null);

		job("", 1000).run();

		assertThat(downloads).isEmpty();
		assertThat(puts).isEmpty();
	}

	@Test
	void null이거나_http로_시작하지_않는_thumbnail_url은_후보에서_제외된다() {
		seed("NO_URL", null, null, null);
		seed("INVALID", "exception://", null, null);
		seed("VALID", "https://cdn.example/ok_n.jpg", null, null);

		job().run();

		assertThat(downloads).containsExactly("https://cdn.example/ok_n.jpg");
		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-brand-post/VALID.jpg");
	}

	/**
	 * 핵심 신규 계약 — 상한은 다운로드 시도만 소모하고 스킵은 공짜다. 기존 잡처럼 후보 리스트를
	 * 상한에서 먼저 자르면 "이미 아카이브됨" 행이 창을 잠식해 뒤쪽 미아카이브 꼬리에 도달하지
	 * 못한다(08-12 운영 실측 — author_profile 백로그 잔존).
	 */
	@Test
	void 배치_상한은_다운로드_시도만_소모하고_스킵은_소모하지_않는다() {
		// 이미 아카이브된 행 3건이 후보 앞쪽을 차지해도(상한 2보다 많음) —
		seed("DONE1", "https://cdn.example/a_n.jpg", "monitor-brand-post/DONE1.jpg", "a_n.jpg");
		seed("DONE2", "https://cdn.example/b_n.jpg", "monitor-brand-post/DONE2.jpg", "b_n.jpg");
		seed("DONE3", "https://cdn.example/c_n.jpg", "monitor-brand-post/DONE3.jpg", "c_n.jpg");
		seed("NEW1", "https://cdn.example/d_n.jpg", null, null);
		seed("NEW2", "https://cdn.example/e_n.jpg", null, null);
		seed("NEW3", "https://cdn.example/f_n.jpg", null, null);

		job("https://par.example/o/", 2).run();

		// — 미아카이브 행이 상한(2)만큼 반드시 아카이브된다. 셋째는 다음 스윕으로 이월.
		assertThat(puts).hasSize(2);
		Long archived = db.queryForObject(
				"SELECT count(image_object_path) FROM brand_post_meta WHERE short_code LIKE 'NEW%'", Long.class);
		assertThat(archived).isEqualTo(2);
	}
}
