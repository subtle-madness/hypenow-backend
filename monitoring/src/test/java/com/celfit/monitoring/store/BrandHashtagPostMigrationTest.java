package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.testsupport.TestDb;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 구 해시태그 감지 데이터 이관(2026-08-27 해시태그 직접 수집 설계 §5) 검증 — 컨테이너 기동 시점의
 * DB는 비어 있어 마이그레이션이 no-op으로 지나가므로, <b>마이그레이션 파일 원문을 classpath에서
 * 읽어 다시 실행</b>해 검증한다(테스트가 SQL 사본을 들고 있으면 파일과 조용히 갈린다).
 * 파일명은 UTC 채번이라 글롭으로 찾는다.
 */
class BrandHashtagPostMigrationTest {

	private static final OffsetDateTime SEEN = OffsetDateTime.parse("2026-08-20T00:00:00Z");

	JdbcTemplate db;
	long brandId;

	private static String migrationSql() throws IOException {
		Resource[] found = new PathMatchingResourcePatternResolver()
				.getResources("classpath*:db/migration/V*__brand_hashtag_post_migration.sql");
		assertThat(found).hasSize(1);
		return found[0].getContentAsString(StandardCharsets.UTF_8);
	}

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		brandId = db.queryForObject(
				"INSERT INTO brand_account (username, ig_user_id) VALUES ('cclime_official', '99') RETURNING id",
				Long.class);
	}

	private void insertHashtagPost(String code, String verdict, OffsetDateTime takenAt) {
		db.update("""
				INSERT INTO brand_hashtag_post (brand_id, short_code, matched_tag, author_username, taken_at,
				                                verdict, verdict_source, first_seen_at)
				VALUES (?, ?, 'cclime', ?, ?, ?, 'RULE', ?)""",
				brandId, code, "poster_" + code, takenAt, verdict, SEEN);
	}

	private Set<String> migratedCodes() {
		return Set.copyOf(db.queryForList(
				"SELECT short_code FROM brand_tagged_post WHERE brand_id = ? AND hashtag_detected_at IS NOT NULL",
				String.class, brandId));
	}

	/** verdict 무관 전량 이관 — 구 LLM 판정은 폐기됐으므로 IRRELEVANT도 새 풀에 들어간다. */
	@Test
	void verdict와_무관하게_이관한다() throws IOException {
		insertHashtagPost("REL", "RELEVANT", OffsetDateTime.parse("2026-08-19T00:00:00Z"));
		insertHashtagPost("IRR", "IRRELEVANT", OffsetDateTime.parse("2026-08-18T00:00:00Z"));
		insertHashtagPost("UNC", "UNCERTAIN", OffsetDateTime.parse("2026-08-17T00:00:00Z"));

		db.execute(migrationSql());

		assertThat(migratedCodes()).containsExactlyInAnyOrder("REL", "IRR", "UNC");
	}

	/** SELF(브랜드 본인)만 제외 — 새 수집 규칙의 본인 제외와 정합. */
	@Test
	void SELF_판정분은_이관하지_않는다() throws IOException {
		insertHashtagPost("SELF1", "SELF", OffsetDateTime.parse("2026-08-19T00:00:00Z"));

		db.execute(migrationSql());

		assertThat(migratedCodes()).isEmpty();
	}

	/** 이미 tagged로 있던 행(겹침)은 hashtag 성분만 얹고 tag_detected_at을 보존한다. */
	@Test
	void 겹침_행은_hashtag_성분만_병기된다() throws IOException {
		db.update("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, taken_at, tag_detected_at)
				VALUES (?, 'BOTH', 'poster_BOTH', ?, ?)""",
				brandId, OffsetDateTime.parse("2026-08-19T00:00:00Z"), SEEN);
		insertHashtagPost("BOTH", "DIRECT_TAGGED", OffsetDateTime.parse("2026-08-19T00:00:00Z"));

		db.execute(migrationSql());

		assertThat(db.queryForObject(
				"SELECT tag_detected_at IS NOT NULL AND hashtag_detected_at IS NOT NULL"
						+ " FROM brand_tagged_post WHERE brand_id = ? AND short_code = 'BOTH'",
				Boolean.class, brandId)).isTrue();
	}

	/** 브랜드당 최신순 1000 상한 — 넘치는 오래된 분은 이관하지 않는다. */
	@Test
	void 브랜드당_최신_1000건까지만_이관한다() throws IOException {
		for (int i = 0; i < 1005; i++) {
			insertHashtagPost("C" + i, "RELEVANT",
					OffsetDateTime.parse("2026-08-20T00:00:00Z").minusMinutes(i));
		}

		db.execute(migrationSql());

		assertThat(migratedCodes()).hasSize(1000).contains("C0", "C999").doesNotContain("C1000", "C1004");
	}

	/** 매칭 태그도 함께 옮긴다 — 이게 없으면 이관분이 was 격리 필터를 통과하지 못한다. */
	@Test
	void 매칭_태그를_새_테이블로_옮긴다() throws IOException {
		insertHashtagPost("REL", "RELEVANT", OffsetDateTime.parse("2026-08-19T00:00:00Z"));
		db.update("INSERT INTO brand_hashtag_post_matched_tags (brand_id, short_code, tag) VALUES (?, 'REL', '끌리메')",
				brandId);

		db.execute(migrationSql());

		assertThat(db.queryForList(
				"SELECT tag FROM brand_post_matched_tag WHERE brand_id = ? AND short_code = 'REL'",
				String.class, brandId)).containsExactlyInAnyOrder("cclime", "끌리메");
	}

	/** 이관분은 미보강(enriched_at NULL)이라 was 표시 게이트를 아직 통과하지 않는다(스윕이 충전한다). */
	@Test
	void 이관분은_미보강_상태로_들어온다() throws IOException {
		insertHashtagPost("REL", "RELEVANT", OffsetDateTime.parse("2026-08-19T00:00:00Z"));

		db.execute(migrationSql());

		assertThat(db.queryForObject(
				"SELECT enriched_at IS NULL AND last_crawled_at IS NULL"
						+ " FROM brand_tagged_post WHERE brand_id = ? AND short_code = 'REL'",
				Boolean.class, brandId)).isTrue();
	}

	/** 재실행 안전 — 롤포워드·수동 재적용에서 중복 키로 죽지 않는다. */
	@Test
	void 두_번_실행해도_멱등이다() throws IOException {
		insertHashtagPost("REL", "RELEVANT", OffsetDateTime.parse("2026-08-19T00:00:00Z"));

		db.execute(migrationSql());
		db.execute(migrationSql());

		assertThat(migratedCodes()).containsExactly("REL");
		assertThat(db.queryForObject(
				"SELECT count(*) FROM brand_post_matched_tag WHERE brand_id = ?", Integer.class, brandId))
				.isEqualTo(1);
	}

	/** 매칭 태그는 이관된 행에만 붙는다 — 상한·SELF로 빠진 행의 태그를 옮기면 FK가 터진다. */
	@Test
	void 이관되지_않은_행의_매칭_태그는_옮기지_않는다() throws IOException {
		insertHashtagPost("SELF1", "SELF", OffsetDateTime.parse("2026-08-19T00:00:00Z"));
		db.update("INSERT INTO brand_hashtag_post_matched_tags (brand_id, short_code, tag) VALUES (?, 'SELF1', '끌리메')",
				brandId);

		db.execute(migrationSql());

		assertThat(db.queryForList(
				"SELECT tag FROM brand_post_matched_tag WHERE brand_id = ? AND short_code = 'SELF1'",
				String.class, brandId)).isEmpty();
	}

	@Test
	void 이관_대상이_없으면_아무것도_하지_않는다() throws IOException {
		db.execute(migrationSql());

		assertThat(migratedCodes()).isEmpty();
	}
}
