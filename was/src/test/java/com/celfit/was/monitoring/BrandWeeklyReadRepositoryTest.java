package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 브랜드 주간 조회(설계 §4) — 지난주 발견분·미표기 판정. BrandReadRepositoryTest와 같은
 * 관용구(monitoring-brand-schema.sql 픽스처 + 직접 시드)를 따른다.
 */
class BrandWeeklyReadRepositoryTest extends IntegrationTest {

	private static final WeekWindow WEEK = new WeekWindow(LocalDate.of(2026, 8, 17));
	private static final OffsetDateTime IN_WEEK =
			OffsetDateTime.of(2026, 8, 19, 12, 0, 0, 0, ZoneOffset.ofHours(9));
	private static final OffsetDateTime BEFORE_WEEK =
			OffsetDateTime.of(2026, 8, 16, 23, 59, 0, 0, ZoneOffset.ofHours(9));
	private static final OffsetDateTime AFTER_WEEK =
			OffsetDateTime.of(2026, 8, 24, 0, 0, 1, 0, ZoneOffset.ofHours(9));

	@Autowired
	DataSource dataSource;

	JdbcClient jdbc;
	BrandReadRepository repository;
	long brandId;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-brand-schema.sql"));
		}
		jdbc = JdbcClient.create(dataSource);
		jdbc.sql("""
				TRUNCATE brand_tagged_post, brand_account, brand_post_meta, brand_post_snapshot,
				         brand_post_comment, author_profile, brand_hashtag_post, brand_hashtag_exclusion,
				         brand_seeded_account
				         RESTART IDENTITY CASCADE
				""").update();
		repository = new BrandReadRepository(jdbc);
		brandId = jdbc.sql("""
				INSERT INTO brand_account (username, ig_user_id) VALUES ('weekly_brand', '1') RETURNING id
				""").query(Long.class).single();
	}

	private void seedTagged(String shortCode, OffsetDateTime tagDetectedAt, OffsetDateTime directRegisteredAt) {
		jdbc.sql("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, taken_at,
				                               tag_detected_at, direct_registered_at)
				VALUES (:brandId, :shortCode, 'author_a', :takenAt, :tagDetectedAt, :directRegisteredAt)
				""")
				.param("brandId", brandId).param("shortCode", shortCode).param("takenAt", IN_WEEK)
				.param("tagDetectedAt", tagDetectedAt).param("directRegisteredAt", directRegisteredAt)
				.update();
	}

	private void seedSnapshot(String shortCode, LocalDate capturedOn, String contentType, Long views, Long likes) {
		jdbc.sql("""
				INSERT INTO brand_post_snapshot (username, short_code, captured_on, content_type, views, likes, comments)
				VALUES ('author_a', :shortCode, :capturedOn, :contentType, :views, :likes, 7)
				""")
				.param("shortCode", shortCode).param("capturedOn", capturedOn)
				.param("contentType", contentType).param("views", views).param("likes", likes)
				.update();
	}

	private void seedMeta(String shortCode, String adVerdict, OffsetDateTime adJudgedAt) {
		jdbc.sql("""
				INSERT INTO brand_post_meta (short_code, username, uploaded_at, caption, ad_verdict, ad_judged_at)
				VALUES (:shortCode, 'author_a', DATE '2026-08-19', '캡션', :adVerdict, :adJudgedAt)
				""")
				.param("shortCode", shortCode).param("adVerdict", adVerdict).param("adJudgedAt", adJudgedAt)
				.update();
	}

	@Test
	void 태그_발견분은_창_안_tag_detected_at만_돌려준다() {
		seedTagged("SC_IN", IN_WEEK, null);
		seedTagged("SC_BEFORE", BEFORE_WEEK, null);
		seedTagged("SC_AFTER", AFTER_WEEK, null);

		List<WeeklyPostMetrics> found = repository.findTaggedPostsDiscoveredBetween(
				List.of(brandId), WEEK.from(), WEEK.toExclusive());

		assertThat(found).extracting(WeeklyPostMetrics::shortCode).containsExactly("SC_IN");
	}

	@Test
	void direct_등록분은_발견분에서_제외된다() {
		seedTagged("SC_TAG", IN_WEEK, null);
		seedTagged("SC_DIRECT", IN_WEEK, IN_WEEK);

		List<WeeklyPostMetrics> found = repository.findTaggedPostsDiscoveredBetween(
				List.of(brandId), WEEK.from(), WEEK.toExclusive());

		assertThat(found).extracting(WeeklyPostMetrics::shortCode).containsExactly("SC_TAG");
	}

	@Test
	void 태그_발견분_지표는_최신_스냅샷_1행이다() {
		seedTagged("SC_IN", IN_WEEK, null);
		seedSnapshot("SC_IN", LocalDate.of(2026, 8, 19), "REELS", 100L, 10L);
		seedSnapshot("SC_IN", LocalDate.of(2026, 8, 21), "REELS", 300L, 30L);

		List<WeeklyPostMetrics> found = repository.findTaggedPostsDiscoveredBetween(
				List.of(brandId), WEEK.from(), WEEK.toExclusive());

		assertThat(found).singleElement().satisfies(post -> {
			assertThat(post.views()).isEqualTo(300L);
			assertThat(post.likes()).isEqualTo(30L);
			assertThat(post.contentType()).isEqualTo("REELS");
			assertThat(post.authorUsername()).isEqualTo("author_a");
		});
	}

	@Test
	void 스냅샷이_없는_발견분도_모수에_남는다() {
		seedTagged("SC_IN", IN_WEEK, null);

		List<WeeklyPostMetrics> found = repository.findTaggedPostsDiscoveredBetween(
				List.of(brandId), WEEK.from(), WEEK.toExclusive());

		assertThat(found).singleElement().satisfies(post -> {
			assertThat(post.shortCode()).isEqualTo("SC_IN");
			assertThat(post.views()).isNull();
			assertThat(post.likes()).isNull();
		});
	}

	@Test
	void 빈_브랜드_목록은_조회하지_않고_빈_리스트() {
		assertThat(repository.findTaggedPostsDiscoveredBetween(List.of(), WEEK.from(), WEEK.toExclusive()))
				.isEmpty();
		assertThat(repository.findHashtagPostsDiscoveredBetween(List.of(), WEEK.from(), WEEK.toExclusive()))
				.isEmpty();
	}

	@Test
	void 해시태그_발견분은_RELEVANT_창_안_first_seen_at만_돌려준다() {
		jdbc.sql("""
				INSERT INTO brand_hashtag_post (brand_id, short_code, matched_tag, author_username, taken_at,
				                                content_type, likes, comments, verdict, verdict_source, first_seen_at)
				VALUES (:brandId, 'HS_IN', '#brand', 'author_b', :takenAt, 'FEED', 11, 2, 'RELEVANT', 'RULE', :inWeek),
				       (:brandId, 'HS_OUT', '#brand', 'author_b', :takenAt, 'FEED', 11, 2, 'RELEVANT', 'RULE', :beforeWeek),
				       (:brandId, 'HS_SELF', '#brand', 'author_b', :takenAt, 'FEED', 11, 2, 'SELF', 'RULE', :inWeek)
				""")
				.param("brandId", brandId).param("takenAt", IN_WEEK)
				.param("inWeek", IN_WEEK).param("beforeWeek", BEFORE_WEEK)
				.update();

		List<WeeklyPostMetrics> found = repository.findHashtagPostsDiscoveredBetween(
				List.of(brandId), WEEK.from(), WEEK.toExclusive());

		assertThat(found).singleElement().satisfies(post -> {
			assertThat(post.shortCode()).isEqualTo("HS_IN");
			assertThat(post.views()).isNull();          // 해시태그 발견분은 스냅샷이 없다(스펙 §5 보류)
			assertThat(post.likes()).isEqualTo(11L);
			assertThat(post.comments()).isEqualTo(2L);
		});
	}

	@Test
	void 미표기_판정은_NOT_DISCLOSED와_창_안_ad_judged_at만_돌려준다() {
		// 2026-08-28 재리뷰 nit — shortcode 후보 바인드가 사라지고 창만으로 걸러 읽는다(등록 원장
		// 교집합은 이제 호출부 WeeklyDigestJob이 자바에서 계산).
		seedMeta("SC_ND", "NOT_DISCLOSED", IN_WEEK);
		seedMeta("SC_OK", "DISCLOSED", IN_WEEK);
		seedMeta("SC_OLD", "NOT_DISCLOSED", BEFORE_WEEK);
		seedMeta("SC_UNJUDGED", null, null);

		List<String> found = repository.findNotDisclosedJudgedBetween(WEEK.from(), WEEK.toExclusive());

		assertThat(found).containsExactly("SC_ND");
	}
}
