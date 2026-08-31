package com.celfit.monitoring.image;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.instagram.source.HikerBackend;
import com.celfit.instagram.source.HikerFetchException;
import com.celfit.instagram.source.HikerHttp;
import com.celfit.monitoring.store.AuthorProfileRepository;
import com.celfit.monitoring.testsupport.CdnUrls;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 만료 CDN 프로필 이미지 재수집 백필 계약(2026-08-25, {@link AuthorImageBackfillJob} 참고):
 * ① author_profile 만료 행은 Hiker 재조회로 profile_pic_url을 갱신한다
 * ② 미만료 행은 Hiker를 호출하지 않는다
 * ③ 해시태그 작성자는 author_profile의 미만료 URL을 우선 재사용(Hiker 호출 0)하고, 없으면
 *   fetchProfile을 호출해 그 작성자의 <b>미아카이브</b> 행만 갱신한다(이미 아카이브된 행은 불변)
 * ④ 한 건 조회 실패가 나머지 대상 처리를 막지 않는다(건 단위 격리)
 * ⑤ limit은 두 phase가 공유하는 Hiker 호출 총량 상한 — 소진분은 deferred로 이월
 * ⑥ 비공개 계정(PrivateAccountException)은 실패와 동일하게 skip(깨진 이미지보다 없음이 낫다)
 *
 * <p>즉시 아카이브 잡(authorArchiveJob·hashtagArchiveJob)은 빈 PAR URL(no-op)로 구성해 이 테스트의
 * 관심사(Hiker 재조회·DB 갱신)와 격리한다 — 아카이브 동작 자체는 각 잡의 전용 테스트가 검증한다.
 */
class AuthorImageBackfillJobTest {

	JdbcTemplate db;
	final List<String> calledPaths = new CopyOnWriteArrayList<>();
	Map<String, String> authorByIdResponses;
	Map<String, String> profileByUsernameResponses;

	@BeforeEach
	void setUp() {
		var pg = TestDb.container();
		var ds = TestDb.dataSource(pg);
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		db.update("TRUNCATE author_profile");
		db.update("TRUNCATE brand_hashtag_post, brand_hashtag, brand_hashtag_exclusion, brand_account CASCADE");
		calledPaths.clear();
		authorByIdResponses = new HashMap<>();
		profileByUsernameResponses = new HashMap<>();
	}

	// ─── 픽스처 헬퍼 ───

	void seedAuthor(String igUserId, String username, String profilePicUrl) {
		db.update("""
				INSERT INTO author_profile (ig_user_id, username, profile_pic_url, fetched_at)
				VALUES (?, ?, ?, now())
				""", igUserId, username, profilePicUrl);
	}

	long seedBrand(String username) {
		return db.queryForObject("""
				INSERT INTO brand_account (username, ig_user_id, status)
				VALUES (?, ?, 'ACTIVE') RETURNING id
				""", Long.class, username, String.valueOf(username.hashCode()));
	}

	void seedHashtagPost(long brandId, String shortCode, String authorUsername, String authorProfilePicUrl,
			String authorImageObjectPath) {
		db.update("""
				INSERT INTO brand_hashtag_post (brand_id, short_code, matched_tag, author_username,
				                                author_profile_pic_url, taken_at, caption, verdict,
				                                verdict_source, author_image_object_path)
				VALUES (?, ?, '#tag', ?, ?, ?, '', 'RELEVANT', 'RULE', ?)
				""", brandId, shortCode, authorUsername, authorProfilePicUrl,
				OffsetDateTime.parse("2026-08-10T00:00:00Z"), authorImageObjectPath);
	}

	String authorPicUrl(String igUserId) {
		return db.queryForObject("SELECT profile_pic_url FROM author_profile WHERE ig_user_id = ?", String.class,
				igUserId);
	}

	String hashtagPicUrl(String shortCode) {
		return db.queryForObject("SELECT author_profile_pic_url FROM brand_hashtag_post WHERE short_code = ?",
				String.class, shortCode);
	}

	// ─── HikerBackend 스텁 — HikerBackendTest와 동일하게 HikerHttp 람다로 응답을 흉내낸다 ───

	AuthorImageBackfillJob job() {
		HikerHttp http = path -> {
			calledPaths.add(path);
			if (path.startsWith("/v2/user/by/id?id=")) {
				String id = path.substring("/v2/user/by/id?id=".length());
				String body = authorByIdResponses.get(id);
				if (body == null) {
					throw new HikerFetchException("의도된 테스트 실패(픽스처 없음): id=" + id);
				}
				return body;
			}
			if (path.startsWith("/v2/user/by/username?username=")) {
				String username = path.substring("/v2/user/by/username?username=".length());
				String body = profileByUsernameResponses.get(username);
				if (body == null) {
					throw new HikerFetchException("의도된 테스트 실패(픽스처 없음): username=" + username);
				}
				return body;
			}
			throw new IllegalStateException("예상치 못한 경로: " + path);
		};
		HikerBackend hikerClient = new HikerBackend(http);
		AuthorProfileRepository authorProfileRepo = new AuthorProfileRepository(db);
		// PAR 미설정 = no-op(AuthorProfileImageArchiveJobTest·HashtagPostAuthorImageArchiveJobTest 관용구) —
		// 즉시 아카이브 단계가 이 테스트의 Hiker 재조회·DB 갱신 검증에 끼어들지 않게 한다.
		var authorArchive = new AuthorProfileImageArchiveJob(db, (p, b, c, cc) -> {}, url -> {
			throw new IllegalStateException("no-op 잡이 다운로드를 시도해선 안 된다");
		}, "");
		var hashtagArchive = new HashtagPostAuthorImageArchiveJob(db, (p, b, c, cc) -> {}, url -> {
			throw new IllegalStateException("no-op 잡이 다운로드를 시도해선 안 된다");
		}, "");
		return new AuthorImageBackfillJob(db, hikerClient, authorProfileRepo, authorArchive, hashtagArchive);
	}

	static String authorByIdJson(String igUserId, String username, String profilePicUrl) {
		return """
				{"user":{"pk":%s,"username":"%s","profile_pic_url":"%s","is_private":false}}
				""".formatted(igUserId, username, profilePicUrl);
	}

	static String profileJson(String username, String profilePicUrl) {
		return """
				{"user":{"pk":1,"username":"%s","profile_pic_url":"%s","is_private":false},"status":"ok"}
				""".formatted(username, profilePicUrl);
	}

	static String privateProfileJson(String username) {
		return """
				{"user":{"pk":1,"username":"%s","is_private":true},"status":"ok"}
				""".formatted(username);
	}

	// ─── Phase A: author_profile ───

	@Test
	void 만료된_author_profile_행은_Hiker_재조회로_profile_pic_url을_갱신한다() {
		seedAuthor("1", "olduser", CdnUrls.expiringIn("old_n.jpg", -3600));
		String freshUrl = CdnUrls.expiringIn("new_n.jpg", 86400);
		authorByIdResponses.put("1", authorByIdJson("1", "olduser", freshUrl));

		var result = job().run(10);

		assertThat(calledPaths).anyMatch(p -> p.startsWith("/v2/user/by/id?id=1"));
		assertThat(authorPicUrl("1")).isEqualTo(freshUrl);
		assertThat(result.authorProfile().refreshed()).isEqualTo(1);
		assertThat(result.authorProfile().failed()).isZero();
		assertThat(result.authorProfile().deferred()).isZero();
	}

	@Test
	void 미만료_author_profile_행은_Hiker를_호출하지_않는다() {
		seedAuthor("1", "liveuser", CdnUrls.expiringIn("live_n.jpg", 86400));

		var result = job().run(10);

		assertThat(calledPaths).isEmpty();
		assertThat(result.authorProfile().refreshed()).isZero();
	}

	@Test
	void 한_건_조회_실패가_나머지_게시자_처리를_막지_않는다() {
		seedAuthor("1", "bad", CdnUrls.expiringIn("bad_n.jpg", -3600));
		seedAuthor("2", "good", CdnUrls.expiringIn("good_n.jpg", -3600));
		String freshUrl = CdnUrls.expiringIn("good_new_n.jpg", 86400);
		// "1"은 픽스처를 등록하지 않아 HikerFetchException으로 실패하도록 둔다.
		authorByIdResponses.put("2", authorByIdJson("2", "good", freshUrl));

		var result = job().run(10);

		assertThat(authorPicUrl("1")).contains("bad_n.jpg");   // 실패분은 원본 유지(다음 트리거가 재대상)
		assertThat(authorPicUrl("2")).isEqualTo(freshUrl);
		assertThat(result.authorProfile().refreshed()).isEqualTo(1);
		assertThat(result.authorProfile().failed()).isEqualTo(1);
	}

	@Test
	void limit은_두_phase가_공유하는_Hiker_호출_총량_상한이고_소진분은_이월된다() {
		seedAuthor("1", "a", CdnUrls.expiringIn("a_n.jpg", -3600));
		seedAuthor("2", "b", CdnUrls.expiringIn("b_n.jpg", -3600));
		authorByIdResponses.put("1", authorByIdJson("1", "a", CdnUrls.expiringIn("a_new_n.jpg", 86400)));
		authorByIdResponses.put("2", authorByIdJson("2", "b", CdnUrls.expiringIn("b_new_n.jpg", 86400)));

		var result = job().run(1);

		assertThat(calledPaths).hasSize(1);   // 상한 1 — 둘째는 호출조차 되지 않는다
		assertThat(result.authorProfile().refreshed()).isEqualTo(1);
		assertThat(result.authorProfile().deferred()).isEqualTo(1);
	}

	// ─── Phase B: brand_hashtag_post(RELEVANT) ───

	@Test
	void 해시태그_작성자는_author_profile의_미만료_URL을_재사용하고_Hiker를_호출하지_않는다() {
		String freshUrl = CdnUrls.expiringIn("fresh_n.jpg", 86400);
		seedAuthor("10", "author_x", freshUrl);   // author_profile은 이미 신선(Phase A가 갱신했다고 가정)
		long brand = seedBrand("brand_a");
		seedHashtagPost(brand, "SC1", "author_x", CdnUrls.expiringIn("stale_n.jpg", -3600), null);

		var result = job().run(10);

		assertThat(calledPaths).isEmpty();
		assertThat(hashtagPicUrl("SC1")).isEqualTo(freshUrl);
		assertThat(result.hashtagAuthor().reused()).isEqualTo(1);
		assertThat(result.hashtagAuthor().refreshed()).isZero();
	}

	@Test
	void author_profile에_미만료_URL이_없으면_fetchProfile을_호출하고_미아카이브_행만_갱신한다() {
		long brand = seedBrand("brand_a");
		seedHashtagPost(brand, "SC1", "author_y", CdnUrls.expiringIn("stale1_n.jpg", -3600), null);   // 미아카이브 — 후보
		// 이미 아카이브된 행 — 후보에서 제외되고 UPDATE도 타지 않아야 한다.
		String archivedUrl = CdnUrls.expiringIn("stale2_n.jpg", -3600);
		seedHashtagPost(brand, "SC2", "author_y", archivedUrl, "monitor-hashtag-author/author_y.jpg");
		String freshUrl = CdnUrls.expiringIn("y_new_n.jpg", 86400);
		profileByUsernameResponses.put("author_y", profileJson("author_y", freshUrl));

		var result = job().run(10);

		assertThat(calledPaths).anyMatch(p -> p.startsWith("/v2/user/by/username?username=author_y"));
		assertThat(hashtagPicUrl("SC1")).isEqualTo(freshUrl);
		assertThat(hashtagPicUrl("SC2")).isEqualTo(archivedUrl);   // 이미 아카이브된 행은 불변
		assertThat(result.hashtagAuthor().refreshed()).isEqualTo(1);
	}

	@Test
	void 비공개_계정은_실패로_카운트되고_URL은_원본_유지된다() {
		long brand = seedBrand("brand_a");
		String staleUrl = CdnUrls.expiringIn("stale_n.jpg", -3600);
		seedHashtagPost(brand, "SC1", "author_priv", staleUrl, null);
		profileByUsernameResponses.put("author_priv", privateProfileJson("author_priv"));

		var result = job().run(10);

		assertThat(hashtagPicUrl("SC1")).isEqualTo(staleUrl);
		assertThat(result.hashtagAuthor().failed()).isEqualTo(1);
		assertThat(result.hashtagAuthor().refreshed()).isZero();
	}

	@Test
	void 미만료_해시태그_작성자_행은_후보에서_제외된다() {
		long brand = seedBrand("brand_a");
		seedHashtagPost(brand, "SC1", "author_live", CdnUrls.expiringIn("live_n.jpg", 86400), null);

		var result = job().run(10);

		assertThat(calledPaths).isEmpty();
		assertThat(result.hashtagAuthor().refreshed()).isZero();
		assertThat(result.hashtagAuthor().reused()).isZero();
	}
}
