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
 * 브랜드 본인 프로필 이미지 아카이브 잡 계약({@link AuthorProfileImageArchiveJob}과 동형 — 대상만
 * brand_account): ① 신규 아카이브(object_path·source_name·archived_at 기록, 키는 ig_user_id 기준)
 * ② source_name 미변경 시 재다운로드 스킵 ③ 쿼리스트링만 다르고 파일명이 같으면 스킵
 * ④ 한 건 실패 격리(계속 진행) ⑤ PAR 미설정 시 no-op ⑥ http(s) 아닌/ null profile_pic_url은 후보 제외
 * ⑦ 배치 상한은 다운로드 시도만 소모(스킵 공짜 — 창 잠식 결함 재발 방지)
 * ⑧ 만료(oe) URL은 시도 없이 제외하고 상한도 소모하지 않는다 — 그래서 만료가 무관한 픽스처의 oe는
 *   CdnUrls.farFutureOe()(실행 시점 +10년)로 만든다 — 절대값 리터럴은 2038년에 일제 파손.
 */
class BrandProfileImageArchiveJobTest {

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

	BrandProfileImageArchiveJob job(String parUrl, int batchLimit) {
		return new BrandProfileImageArchiveJob(db, fakeStore(), fakeDownloader(), parUrl, batchLimit);
	}

	BrandProfileImageArchiveJob job() {
		return job("https://par.example/o/", 1000);
	}

	@BeforeEach
	void setUp() {
		var pg = TestDb.container();
		var ds = TestDb.dataSource(pg);
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		db.update("TRUNCATE brand_account CASCADE");
		downloads.clear();
		puts.clear();
		failUrls.clear();
	}

	void seedBrand(String igUserId, String username, String profilePicUrl, String imageObjectPath,
			String imageSourceName) {
		db.update("""
				INSERT INTO brand_account (username, ig_user_id, profile_pic_url,
				                           image_object_path, image_source_name)
				VALUES (?, ?, ?, ?, ?)
				""", username, igUserId, profilePicUrl, imageObjectPath, imageSourceName);
	}

	@Test
	void 신규_아카이브는_ig_user_id_키로_object_path_source_name_archived_at을_기록한다() {
		seedBrand("17841400000001", "glowdeep_official", "https://cdn.example/v/t51/463_111_n.jpg?" + CdnUrls.farFutureOe(),
				null, null);

		job().run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-brand/17841400000001.jpg");
		assertThat(puts.get(0).get("cacheControl")).isEqualTo("public, max-age=86400");
		var row = db.queryForMap("""
				SELECT image_object_path, image_source_name, image_archived_at
				FROM brand_account WHERE ig_user_id='17841400000001'
				""");
		assertThat(row.get("image_object_path")).isEqualTo("monitor-brand/17841400000001.jpg");
		assertThat(row.get("image_source_name")).isEqualTo("463_111_n.jpg");
		assertThat(row.get("image_archived_at")).isNotNull();
	}

	@Test
	void source_name이_바뀌지_않으면_재다운로드를_스킵한다() {
		seedBrand("1", "glowdeep_official", "https://cdn.example/v/463_111_n.jpg?" + CdnUrls.farFutureOe(),
				"monitor-brand/1.jpg", "463_111_n.jpg");

		job().run();

		assertThat(downloads).isEmpty();
		assertThat(puts).isEmpty();
	}

	/** 핵심 회귀 방지 — 인스타 CDN은 oe=(서명) 쿼리파라미터가 매 조회마다 바뀐다. 파일명만 비교해야 한다. */
	@Test
	void 쿼리스트링만_다르고_파일명이_같으면_스킵한다() {
		seedBrand("1", "glowdeep_official", "https://cdn-a.example/v/999_222_n.jpg?" + CdnUrls.farFutureOe() + "&sig=1",
				"monitor-brand/1.jpg", "999_222_n.jpg");
		db.update("UPDATE brand_account SET profile_pic_url = ? WHERE ig_user_id = '1'",
				"https://cdn-b.example/v/999_222_n.jpg?" + CdnUrls.farFutureOe() + "&sig=99");

		job().run();

		assertThat(downloads).isEmpty();
		assertThat(puts).isEmpty();
	}

	@Test
	void 파일명이_바뀌면_같은_키로_재업로드하고_source_name을_갱신한다() {
		seedBrand("1", "glowdeep_official", "https://cdn.example/v/999_222_n.jpg?" + CdnUrls.farFutureOe(),
				"monitor-brand/1.jpg", "999_222_n.jpg");
		db.update("UPDATE brand_account SET profile_pic_url = ? WHERE ig_user_id = '1'",
				"https://cdn.example/v/1000_333_n.jpg?" + CdnUrls.farFutureOe());

		job().run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-brand/1.jpg");
		String sourceName = db.queryForObject(
				"SELECT image_source_name FROM brand_account WHERE ig_user_id='1'", String.class);
		assertThat(sourceName).isEqualTo("1000_333_n.jpg");
	}

	@Test
	void 한_건_다운로드_실패가_나머지_브랜드를_막지_않는다() {
		seedBrand("1", "bad_brand", "https://cdn.example/expired_n.jpg", null, null);
		seedBrand("2", "good_brand", "https://cdn.example/ok_n.jpg", null, null);
		failUrls.add("https://cdn.example/expired_n.jpg");

		job().run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-brand/2.jpg");
		String badPath = db.queryForObject(
				"SELECT image_object_path FROM brand_account WHERE ig_user_id='1'", String.class);
		assertThat(badPath).isNull();   // 실패분은 기록되지 않아 다음 실행에서 재대상
	}

	@Test
	void PAR_미설정이면_no_op이다() {
		seedBrand("1", "glowdeep_official", "https://cdn.example/1_n.jpg", null, null);

		job("", 1000).run();

		assertThat(downloads).isEmpty();
		assertThat(puts).isEmpty();
	}

	@Test
	void null이거나_http로_시작하지_않는_profile_pic_url은_후보에서_제외된다() {
		seedBrand("1", "no_url", null, null, null);
		seedBrand("2", "invalid_scheme", "exception://", null, null);
		seedBrand("3", "valid", "https://cdn.example/ok_n.jpg", null, null);

		job().run();

		assertThat(downloads).containsExactly("https://cdn.example/ok_n.jpg");
		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-brand/3.jpg");
	}

	/**
	 * 핵심 계약({@link BrandPostThumbnailArchiveJobTest}과 동형) — 상한은 다운로드 시도만 소모하고
	 * 스킵은 공짜다. 후보 리스트를 상한에서 먼저 자르면 "이미 아카이브됨" 행이 창을 잠식해 뒤쪽
	 * 미아카이브 꼬리에 도달하지 못한다(08-12 운영 실측 — author_profile 백로그 잔존).
	 */
	@Test
	void 배치_상한은_다운로드_시도만_소모하고_스킵은_소모하지_않는다() {
		// 이미 아카이브된 행 3건이 후보 앞쪽을 차지해도(상한 2보다 많음) —
		seedBrand("101", "done1", "https://cdn.example/a_n.jpg", "monitor-brand/101.jpg", "a_n.jpg");
		seedBrand("102", "done2", "https://cdn.example/b_n.jpg", "monitor-brand/102.jpg", "b_n.jpg");
		seedBrand("103", "done3", "https://cdn.example/c_n.jpg", "monitor-brand/103.jpg", "c_n.jpg");
		seedBrand("201", "new1", "https://cdn.example/d_n.jpg", null, null);
		seedBrand("202", "new2", "https://cdn.example/e_n.jpg", null, null);
		seedBrand("203", "new3", "https://cdn.example/f_n.jpg", null, null);

		job("https://par.example/o/", 2).run();

		// — 미아카이브 행이 상한(2)만큼 반드시 아카이브된다. 셋째는 다음 스윕으로 이월.
		assertThat(puts).hasSize(2);
		Long archived = db.queryForObject(
				"SELECT count(image_object_path) FROM brand_account WHERE ig_user_id LIKE '2%'", Long.class);
		assertThat(archived).isEqualTo(2);
	}

	/** 만료 URL은 재시도해도 영원히 403 — 시도 자체를 걸러야 예산이 미아카이브 꼬리에 도달한다. */
	@Test
	void 만료된_URL은_다운로드_시도조차_하지_않는다() {
		seedBrand("301", "dead", CdnUrls.expiringIn("dead_n.jpg", -3600), null, null);
		seedBrand("302", "live", CdnUrls.expiringIn("live_n.jpg", 86400), null, null);
		seedBrand("303", "unknown", CdnUrls.noOe("unknown_n.jpg"), null, null);   // oe 없음 → 시도 유지

		job().run();

		assertThat(downloads).noneMatch(u -> u.contains("dead_n.jpg"));
		assertThat(puts).extracting(m -> m.get("path"))
				.containsExactlyInAnyOrder("monitor-brand/302.jpg", "monitor-brand/303.jpg");
	}

	@Test
	void 만료된_URL은_배치_상한을_소모하지_않는다() {
		seedBrand("301", "dead1", CdnUrls.expiringIn("dead1_n.jpg", -3600), null, null);
		seedBrand("302", "dead2", CdnUrls.expiringIn("dead2_n.jpg", -3600), null, null);
		seedBrand("303", "live1", CdnUrls.expiringIn("live1_n.jpg", 86400), null, null);
		seedBrand("304", "live2", CdnUrls.expiringIn("live2_n.jpg", 86400), null, null);

		job("https://par.example/o/", 2).run();

		assertThat(puts).extracting(m -> m.get("path"))
				.containsExactlyInAnyOrder("monitor-brand/303.jpg", "monitor-brand/304.jpg");
	}

	/** 상한이 걸리면 먼저 죽을 URL부터 — 임박분을 이월하면 다음 스윕엔 이미 만료돼 영구 유실된다. */
	@Test
	void 상한이_걸리면_만료_임박_순으로_예산을_쓴다() {
		seedBrand("401", "far", CdnUrls.expiringIn("far_n.jpg", 86400 * 3), null, null);
		seedBrand("402", "soon", CdnUrls.expiringIn("soon_n.jpg", 3600), null, null);
		seedBrand("403", "unknown", CdnUrls.noOe("unknown_n.jpg"), null, null);

		job("https://par.example/o/", 1).run();

		assertThat(puts).extracting(m -> m.get("path")).containsExactly("monitor-brand/402.jpg");
	}
}
