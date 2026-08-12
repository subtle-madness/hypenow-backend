package com.celfit.monitoring.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.testsupport.TestDb;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 프로필 이미지 아카이브 잡 계약(설계 스펙 §3-1):
 * ① 신규 아카이브(object_path·source_name·archived_at 기록) ② source_name 미변경 시 재다운로드 스킵
 * ③ 쿼리스트링만 다르고 파일명이 같으면 스킵(핵심 회귀 방지 — 매일 전량 재다운로드 버그)
 * ④ 한 건 실패 격리(계속 진행) ⑤ PAR 미설정 시 no-op ⑥ http(s) 아닌/ null profile_image_url은 후보 제외
 * ⑦ 배치 상한은 다운로드 시도만 소모(스킵 공짜 — 창 잠식 결함 재발 방지).
 */
class ProfileImageArchiveJobTest {

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

	ProfileImageArchiveJob job(String parUrl, int batchLimit) {
		return new ProfileImageArchiveJob(db, fakeStore(), fakeDownloader(), parUrl, batchLimit);
	}

	ProfileImageArchiveJob job() {
		return job("https://par.example/o/", 1000);
	}

	@BeforeEach
	void setUp() {
		var pg = TestDb.container();
		var ds = TestDb.dataSource(pg);
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		db.update("TRUNCATE profile_meta");
		downloads.clear();
		puts.clear();
		failUrls.clear();
	}

	void seedProfileMeta(String username, String profileImageUrl, String imageObjectPath, String imageSourceName) {
		db.update("""
				INSERT INTO profile_meta (username, profile_image_url, updated_at, image_object_path, image_source_name)
				VALUES (?, ?, now(), ?, ?)
				""", username, profileImageUrl, imageObjectPath, imageSourceName);
	}

	@Test
	void 신규_아카이브는_object_path_source_name_archived_at을_기록한다() {
		seedProfileMeta("glowdeep", "https://cdn.example/v/t51/463_111_n.jpg?oe=abc", null, null);

		job().run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-profile/glowdeep.jpg");
		assertThat(puts.get(0).get("cacheControl")).isEqualTo("public, max-age=86400");
		var row = db.queryForMap("""
				SELECT image_object_path, image_source_name, image_archived_at FROM profile_meta WHERE username='glowdeep'
				""");
		assertThat(row.get("image_object_path")).isEqualTo("monitor-profile/glowdeep.jpg");
		assertThat(row.get("image_source_name")).isEqualTo("463_111_n.jpg");
		assertThat(row.get("image_archived_at")).isNotNull();
	}

	@Test
	void source_name이_바뀌지_않으면_재다운로드를_스킵한다() {
		seedProfileMeta("glowdeep", "https://cdn.example/v/463_111_n.jpg?oe=abc",
				"monitor-profile/glowdeep.jpg", "463_111_n.jpg");

		job().run();

		assertThat(downloads).isEmpty();
		assertThat(puts).isEmpty();
	}

	/** 핵심 회귀 방지 — 인스타 CDN은 oe=(서명) 쿼리파라미터가 매 조회마다 바뀐다. 파일명만 비교해야 한다. */
	@Test
	void 쿼리스트링만_다르고_파일명이_같으면_스킵한다() {
		seedProfileMeta("glowdeep", "https://cdn-a.example/v/999_222_n.jpg?oe=old&sig=1",
				"monitor-profile/glowdeep.jpg", "999_222_n.jpg");
		// 호스트·쿼리만 바뀐 같은 파일명
		db.update("UPDATE profile_meta SET profile_image_url = ? WHERE username = 'glowdeep'",
				"https://cdn-b.example/v/999_222_n.jpg?oe=new&sig=99");

		job().run();

		assertThat(downloads).isEmpty();
		assertThat(puts).isEmpty();
	}

	@Test
	void 파일명이_바뀌면_같은_키로_재업로드하고_source_name을_갱신한다() {
		seedProfileMeta("glowdeep", "https://cdn.example/v/999_222_n.jpg?oe=old",
				"monitor-profile/glowdeep.jpg", "999_222_n.jpg");
		db.update("UPDATE profile_meta SET profile_image_url = ? WHERE username = 'glowdeep'",
				"https://cdn.example/v/1000_333_n.jpg?oe=new");

		job().run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-profile/glowdeep.jpg");
		String sourceName = db.queryForObject(
				"SELECT image_source_name FROM profile_meta WHERE username='glowdeep'", String.class);
		assertThat(sourceName).isEqualTo("1000_333_n.jpg");
	}

	@Test
	void 한_건_다운로드_실패가_나머지_계정을_막지_않는다() {
		seedProfileMeta("bad", "https://cdn.example/expired_n.jpg", null, null);
		seedProfileMeta("good", "https://cdn.example/ok_n.jpg", null, null);
		failUrls.add("https://cdn.example/expired_n.jpg");

		job().run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-profile/good.jpg");
		String badPath = db.queryForObject(
				"SELECT image_object_path FROM profile_meta WHERE username='bad'", String.class);
		assertThat(badPath).isNull();   // 실패분은 기록되지 않아 다음 실행에서 재대상
	}

	@Test
	void PAR_미설정이면_no_op이다() {
		seedProfileMeta("glowdeep", "https://cdn.example/1_n.jpg", null, null);

		job("", 1000).run();

		assertThat(downloads).isEmpty();
		assertThat(puts).isEmpty();
	}

	@Test
	void null이거나_http로_시작하지_않는_profile_image_url은_후보에서_제외된다() {
		seedProfileMeta("no_url", null, null, null);
		seedProfileMeta("invalid_scheme", "exception://", null, null);
		seedProfileMeta("valid", "https://cdn.example/ok_n.jpg", null, null);

		job().run();

		assertThat(downloads).containsExactly("https://cdn.example/ok_n.jpg");
		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-profile/valid.jpg");
	}

	/**
	 * 핵심 계약({@link BrandPostThumbnailArchiveJobTest}과 동형) — 상한은 다운로드 시도만 소모하고
	 * 스킵은 공짜다. 후보 리스트를 상한에서 먼저 자르면 "이미 아카이브됨" 행이 창을 잠식해 뒤쪽
	 * 미아카이브 꼬리에 도달하지 못한다(08-12 운영 실측 — author_profile 백로그 잔존).
	 */
	@Test
	void 배치_상한은_다운로드_시도만_소모하고_스킵은_소모하지_않는다() {
		// 이미 아카이브된 행 3건이 후보 앞쪽을 차지해도(상한 2보다 많음) —
		seedProfileMeta("done1", "https://cdn.example/a_n.jpg", "monitor-profile/done1.jpg", "a_n.jpg");
		seedProfileMeta("done2", "https://cdn.example/b_n.jpg", "monitor-profile/done2.jpg", "b_n.jpg");
		seedProfileMeta("done3", "https://cdn.example/c_n.jpg", "monitor-profile/done3.jpg", "c_n.jpg");
		seedProfileMeta("new1", "https://cdn.example/d_n.jpg", null, null);
		seedProfileMeta("new2", "https://cdn.example/e_n.jpg", null, null);
		seedProfileMeta("new3", "https://cdn.example/f_n.jpg", null, null);

		job("https://par.example/o/", 2).run();

		// — 미아카이브 행이 상한(2)만큼 반드시 아카이브된다. 셋째는 다음 스윕으로 이월.
		assertThat(puts).hasSize(2);
		Long archived = db.queryForObject(
				"SELECT count(image_object_path) FROM profile_meta WHERE username LIKE 'new%'", Long.class);
		assertThat(archived).isEqualTo(2);
	}
}
