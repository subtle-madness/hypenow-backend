package com.celfit.monitoring.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.testsupport.CdnUrls;
import com.celfit.monitoring.testsupport.TestDb;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 게시자 프로필 이미지 아카이브 잡 계약({@link ProfileImageArchiveJob}과 동형 — 대상만 author_profile):
 * ① 신규 아카이브(object_path·source_name·archived_at 기록, 키는 ig_user_id 기준)
 * ② source_name 미변경 시 재다운로드 스킵 ③ 쿼리스트링만 다르고 파일명이 같으면 스킵
 * ④ 한 건 실패 격리(계속 진행) ⑤ PAR 미설정 시 no-op ⑥ http(s) 아닌/ null profile_pic_url은 후보 제외
 * ⑦ 만료(oe) URL은 시도 없이 제외한다(08-25 배치 상한 완전 제거 — 상한 관련 계약은 폐기) —
 *   author_profile은 30일 stale 때만 재조회돼 만료 잔존분 비중이 특히 크다. 만료가 무관한 픽스처의
 *   oe는 CdnUrls.farFutureOe()(실행 시점 +10년)로 만든다 — 절대값 리터럴은 2038년에 일제 파손.
 */
class AuthorProfileImageArchiveJobTest {

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

	AuthorProfileImageArchiveJob job(String parUrl) {
		return new AuthorProfileImageArchiveJob(db, fakeStore(), fakeDownloader(), parUrl);
	}

	AuthorProfileImageArchiveJob job() {
		return job("https://par.example/o/");
	}

	@BeforeEach
	void setUp() {
		var pg = TestDb.container();
		var ds = TestDb.dataSource(pg);
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		db.update("TRUNCATE author_profile");
		downloads.clear();
		puts.clear();
		failUrls.clear();
	}

	void seedAuthor(String igUserId, String username, String profilePicUrl, String imageObjectPath,
			String imageSourceName) {
		db.update("""
				INSERT INTO author_profile (ig_user_id, username, profile_pic_url, fetched_at,
				                            image_object_path, image_source_name)
				VALUES (?, ?, ?, now(), ?, ?)
				""", igUserId, username, profilePicUrl, imageObjectPath, imageSourceName);
	}

	@Test
	void 신규_아카이브는_ig_user_id_키로_object_path_source_name_archived_at을_기록한다() {
		seedAuthor("17841400000001", "glowdeep", "https://cdn.example/v/t51/463_111_n.jpg?" + CdnUrls.farFutureOe(), null, null);

		job().run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-author/17841400000001.jpg");
		assertThat(puts.get(0).get("cacheControl")).isEqualTo("public, max-age=86400");
		var row = db.queryForMap("""
				SELECT image_object_path, image_source_name, image_archived_at
				FROM author_profile WHERE ig_user_id='17841400000001'
				""");
		assertThat(row.get("image_object_path")).isEqualTo("monitor-author/17841400000001.jpg");
		assertThat(row.get("image_source_name")).isEqualTo("463_111_n.jpg");
		assertThat(row.get("image_archived_at")).isNotNull();
	}

	@Test
	void source_name이_바뀌지_않으면_재다운로드를_스킵한다() {
		seedAuthor("1", "glowdeep", "https://cdn.example/v/463_111_n.jpg?" + CdnUrls.farFutureOe(),
				"monitor-author/1.jpg", "463_111_n.jpg");

		job().run();

		assertThat(downloads).isEmpty();
		assertThat(puts).isEmpty();
	}

	/** 핵심 회귀 방지 — 인스타 CDN은 oe=(서명) 쿼리파라미터가 매 조회마다 바뀐다. 파일명만 비교해야 한다. */
	@Test
	void 쿼리스트링만_다르고_파일명이_같으면_스킵한다() {
		seedAuthor("1", "glowdeep", "https://cdn-a.example/v/999_222_n.jpg?" + CdnUrls.farFutureOe() + "&sig=1",
				"monitor-author/1.jpg", "999_222_n.jpg");
		db.update("UPDATE author_profile SET profile_pic_url = ? WHERE ig_user_id = '1'",
				"https://cdn-b.example/v/999_222_n.jpg?" + CdnUrls.farFutureOe() + "&sig=99");

		job().run();

		assertThat(downloads).isEmpty();
		assertThat(puts).isEmpty();
	}

	@Test
	void 파일명이_바뀌면_같은_키로_재업로드하고_source_name을_갱신한다() {
		seedAuthor("1", "glowdeep", "https://cdn.example/v/999_222_n.jpg?" + CdnUrls.farFutureOe(),
				"monitor-author/1.jpg", "999_222_n.jpg");
		db.update("UPDATE author_profile SET profile_pic_url = ? WHERE ig_user_id = '1'",
				"https://cdn.example/v/1000_333_n.jpg?" + CdnUrls.farFutureOe());

		job().run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-author/1.jpg");
		String sourceName = db.queryForObject(
				"SELECT image_source_name FROM author_profile WHERE ig_user_id='1'", String.class);
		assertThat(sourceName).isEqualTo("1000_333_n.jpg");
	}

	@Test
	void 한_건_다운로드_실패가_나머지_게시자를_막지_않는다() {
		seedAuthor("1", "bad", "https://cdn.example/expired_n.jpg", null, null);
		seedAuthor("2", "good", "https://cdn.example/ok_n.jpg", null, null);
		failUrls.add("https://cdn.example/expired_n.jpg");

		job().run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-author/2.jpg");
		String badPath = db.queryForObject(
				"SELECT image_object_path FROM author_profile WHERE ig_user_id='1'", String.class);
		assertThat(badPath).isNull();   // 실패분은 기록되지 않아 다음 실행에서 재대상
	}

	@Test
	void PAR_미설정이면_no_op이다() {
		seedAuthor("1", "glowdeep", "https://cdn.example/1_n.jpg", null, null);

		job("").run();

		assertThat(downloads).isEmpty();
		assertThat(puts).isEmpty();
	}

	@Test
	void null이거나_http로_시작하지_않는_profile_pic_url은_후보에서_제외된다() {
		seedAuthor("1", "no_url", null, null, null);
		seedAuthor("2", "invalid_scheme", "exception://", null, null);
		seedAuthor("3", "valid", "https://cdn.example/ok_n.jpg", null, null);

		job().run();

		assertThat(downloads).containsExactly("https://cdn.example/ok_n.jpg");
		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-author/3.jpg");
	}

	/** 대량 백로그도 한 스윕에서 전량 처리된다(08-25 배치 상한 완전 제거 — 상한 관련 계약은 폐기). */
	@Test
	void 미아카이브_행_전량이_한_스윕에서_처리된다() {
		seedAuthor("101", "done1", "https://cdn.example/a_n.jpg", "monitor-author/101.jpg", "a_n.jpg");
		seedAuthor("201", "new1", "https://cdn.example/d_n.jpg", null, null);
		seedAuthor("202", "new2", "https://cdn.example/e_n.jpg", null, null);
		seedAuthor("203", "new3", "https://cdn.example/f_n.jpg", null, null);

		job().run();

		assertThat(puts).hasSize(3);
		Long archived = db.queryForObject(
				"SELECT count(image_object_path) FROM author_profile WHERE ig_user_id LIKE '2%'", Long.class);
		assertThat(archived).isEqualTo(3);
	}

	/** 만료 URL은 재시도해도 영원히 403 — 시도 자체를 걸러 예산(HTTP 왕복)을 낭비하지 않는다. */
	@Test
	void 만료된_URL은_다운로드_시도조차_하지_않는다() {
		seedAuthor("301", "dead", CdnUrls.expiringIn("dead_n.jpg", -3600), null, null);
		seedAuthor("302", "live", CdnUrls.expiringIn("live_n.jpg", 86400), null, null);
		seedAuthor("303", "unknown", CdnUrls.noOe("unknown_n.jpg"), null, null);   // oe 없음 → 시도 유지

		job().run();

		assertThat(downloads).noneMatch(u -> u.contains("dead_n.jpg"));
		assertThat(puts).extracting(m -> m.get("path"))
				.containsExactlyInAnyOrder("monitor-author/302.jpg", "monitor-author/303.jpg");
	}
}
