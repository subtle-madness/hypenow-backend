package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 태그 장부 백필 마이그레이션(2026-08-27 해시태그 직접 수집 설계 §4) 검증 — 컨테이너 기동 시점의
 * DB는 비어 있어 마이그레이션이 no-op으로 지나가므로, <b>마이그레이션 파일 원문을 classpath에서
 * 읽어 다시 실행</b>해 검증한다(테스트가 SQL 사본을 들고 있으면 파일과 조용히 갈린다).
 * 파일명은 UTC 채번이라 글롭으로 찾는다 — 그래서 이 테스트는 채번 값과 무관하게 계속 유효하다.
 */
class BrandHashtagTagsBackfillMigrationTest extends IntegrationTest {

	@Autowired
	JdbcClient jdbcClient;
	@Autowired
	JdbcTemplate jdbcTemplate;
	@Autowired
	BrandHashtagTagRepository repository;

	long userId;
	long brandId;
	long deletedLinkBrandId;

	/** 백필 SQL 원문 — 마이그레이션 파일이 정확히 1개여야 한다(중복 채번 방지 겸용). */
	private static String backfillSql() throws IOException {
		Resource[] found = new PathMatchingResourcePatternResolver()
				.getResources("classpath*:db/migration/app/V*__brand_hashtag_tags_backfill.sql");
		assertThat(found).hasSize(1);
		return found[0].getContentAsString(StandardCharsets.UTF_8);
	}

	@BeforeEach
	void 링크_시드() {
		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "ledger-backfill-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
		// brand_id는 테스트마다 고유해야 한다 — 통합 테스트가 컨테이너를 공유하고 롤백이 없다.
		brandId = System.nanoTime();
		deletedLinkBrandId = brandId + 1;
	}

	private void insertLink(long linkBrandId, String username, boolean deleted) {
		jdbcClient.sql("""
				INSERT INTO app.brand_monitorings (user_id, brand_id, username, account_type, collection_months,
				                                   deleted_at)
				VALUES (:userId, :brandId, :username, 'own', 12, :deletedAt)
				""")
				.param("userId", userId)
				.param("brandId", linkBrandId)
				.param("username", username)
				.param("deletedAt", deleted ? java.time.OffsetDateTime.now() : null)
				.update();
	}

	@Test
	void 활성_링크의_계정명_유도_태그를_장부에_채운다() throws IOException {
		insertLink(brandId, "cclime_official", false);

		jdbcTemplate.execute(backfillSql());

		assertThat(repository.findByUserAndBrand(userId, brandId)).containsExactly("cclime_official");
	}

	/** IG 해시태그는 점(.)에서 끊긴다 — was BrandHashtagTags.derive와 같은 결과여야 한다. */
	@Test
	void 점_포함_계정명은_점_앞까지만_태그가_된다() throws IOException {
		insertLink(brandId, "cclime.beauty", false);

		jdbcTemplate.execute(backfillSql());

		assertThat(repository.findByUserAndBrand(userId, brandId)).containsExactly("cclime");
	}

	/** 선행 유효 문자가 없으면 태그가 없다 — 빈 문자열 태그를 심으면 안 된다. */
	@Test
	void 무효_문자로_시작하는_계정명은_태그를_만들지_않는다() throws IOException {
		insertLink(brandId, ".beauty", false);

		jdbcTemplate.execute(backfillSql());

		assertThat(repository.findByUserAndBrand(userId, brandId)).isEmpty();
	}

	/** 해제된 연결은 대상이 아니다 — 해제한 사용자의 장부를 되살리면 안 된다. */
	@Test
	void 해제된_링크는_백필하지_않는다() throws IOException {
		insertLink(deletedLinkBrandId, "gone_brand", true);

		jdbcTemplate.execute(backfillSql());

		assertThat(repository.findByUserAndBrand(userId, deletedLinkBrandId)).isEmpty();
	}

	/** 재실행 안전(ON CONFLICT DO NOTHING) — 운영 재적용·롤포워드에서 중복 키로 죽지 않는다. */
	@Test
	void 두_번_실행해도_멱등이다() throws IOException {
		insertLink(brandId, "cclime_official", false);

		jdbcTemplate.execute(backfillSql());
		jdbcTemplate.execute(backfillSql());

		assertThat(repository.findByUserAndBrand(userId, brandId)).containsExactly("cclime_official");
	}

	/** 사용자가 이미 갖고 있는 태그는 그대로 두고 유도 태그만 더한다. */
	@Test
	void 기존_사용자_태그를_덮어쓰지_않는다() throws IOException {
		insertLink(brandId, "cclime_official", false);
		repository.addTags(userId, brandId, java.util.List.of("끌리메"));

		jdbcTemplate.execute(backfillSql());

		assertThat(repository.findByUserAndBrand(userId, brandId))
				.containsExactlyInAnyOrder("끌리메", "cclime_official");
	}
}
