package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.v1.brandmonitoring.BrandAccountType;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 툴 레이어 소유 검증 통합 검증(설계 §4·§9) - 실 DB 위에서 "남의 brandId·shortCode를 넘기면 막히는가"가
 * 필수 케이스다. monitoring 테이블은 was 테스트 픽스처(monitoring-brand-schema.sql)를 같은 컨테이너에
 * 얹어 재현한다(V1BrandDirectPostCancelIntegrationTest와 같은 관용구).
 */
class BrandAiToolboxIntegrationTest extends IntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

	@Autowired
	BrandLinkRepository linkRepository;
	@Autowired
	DataSource dataSource;
	@Autowired
	JdbcClient jdbcClient;
	@Autowired
	ObjectMapper objectMapper;

	BrandAiToolbox toolbox;
	long userId;
	long otherUserId;
	long myBrandId;
	long otherBrandId;

	@BeforeEach
	void setUp() throws SQLException {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-brand-schema.sql"));
		}
		JdbcClient monitoringJdbc = JdbcClient.create(dataSource);
		monitoringJdbc.sql("""
				TRUNCATE brand_tagged_post, brand_account, brand_post_meta, brand_post_snapshot,
				         brand_post_comment, author_profile, brand_hashtag_post
				         RESTART IDENTITY CASCADE
				""").update();
		jdbcClient.sql("TRUNCATE app.brand_monitorings RESTART IDENTITY CASCADE").update();

		toolbox = new BrandAiToolbox(linkRepository, new BrandReadRepository(monitoringJdbc),
				objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), true);

		userId = insertUser();
		otherUserId = insertUser();
		myBrandId = insertBrand(monitoringJdbc, "mybrand");
		otherBrandId = insertBrand(monitoringJdbc, "otherbrand");
		linkRepository.insertLink(userId, myBrandId, "mybrand", BrandAccountType.OWN, 12);
		linkRepository.insertLink(otherUserId, otherBrandId, "otherbrand", BrandAccountType.OWN, 12);

		insertPost(monitoringJdbc, myBrandId, "MINE1", "mine_author");
		insertPost(monitoringJdbc, otherBrandId, "THEIRS1", "their_author");
	}

	private long insertUser() {
		return jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role, name, user_type,
				                       agreed_terms, agreed_privacy, agreed_age14)
				VALUES (:email, 'hash', 'USER', '테스터', 'brand', true, true, true)
				RETURNING id
				""").param("email", UUID.randomUUID() + "@example.com").query(Long.class).single();
	}

	private long insertBrand(JdbcClient monitoringJdbc, String username) {
		return monitoringJdbc.sql("""
				INSERT INTO brand_account (username, ig_user_id, followers, biography, media_count)
				VALUES (:username, :igId, 1000, '브랜드 소개', 42)
				RETURNING id
				""").param("username", username).param("igId", username + "-ig")
				.query(Long.class).single();
	}

	private void insertPost(JdbcClient monitoringJdbc, long brandId, String shortCode, String author) {
		monitoringJdbc.sql("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, author_ig_user_id, taken_at)
				VALUES (:brandId, :shortCode, :author, :author, :takenAt)
				""").param("brandId", brandId).param("shortCode", shortCode).param("author", author)
				.param("takenAt", OffsetDateTime.ofInstant(NOW.minusSeconds(86400), ZoneOffset.UTC))
				.update();
		monitoringJdbc.sql("""
				INSERT INTO brand_post_meta (short_code, username, content_type, uploaded_at, caption,
				                             is_paid_partnership, ad_verdict)
				VALUES (:shortCode, :author, 'reel', DATE '2026-08-26', :caption, true, 'DISCLOSED')
				""").param("shortCode", shortCode).param("author", author)
				.param("caption", shortCode + " 캡션 본문").update();
		monitoringJdbc.sql("""
				INSERT INTO brand_post_snapshot (username, short_code, captured_on, content_type, likes, comments, views)
				VALUES (:author, :shortCode, DATE '2026-08-26', 'reel', 100, 7, 5000)
				""").param("shortCode", shortCode).param("author", author).update();
		monitoringJdbc.sql("""
				INSERT INTO brand_post_comment (short_code, id, author, body, like_count, commented_at)
				VALUES (:shortCode, :shortCode || '-c1', 'fan1', '너무 예뻐요', 3, :at)
				""").param("shortCode", shortCode)
				.param("at", OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)).update();
	}

	private ObjectNode args() {
		return objectMapper.createObjectNode();
	}

	@Test
	void list_brands는_내_브랜드만_돌려준다() {
		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.LIST_BRANDS, args());

		assertThat(result.failed()).isFalse();
		assertThat(result.rowCount()).isEqualTo(1);
		assertThat(result.payloadJson()).contains("mybrand").doesNotContain("otherbrand");
	}

	@Test
	void 남의_brandId로_list_posts하면_실패_결과를_돌려준다() {
		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.LIST_POSTS,
				args().put("brandId", otherBrandId));

		assertThat(result.failed()).isTrue();
		assertThat(result.payloadJson()).contains("접근 권한");
		assertThat(result.rowCount()).isZero();
	}

	@Test
	void 남의_shortCode로_get_post하면_실패_결과를_돌려준다() {
		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.GET_POST,
				args().put("shortCode", "THEIRS1"));

		assertThat(result.failed()).isTrue();
		assertThat(result.payloadJson()).doesNotContain("THEIRS1 캡션 본문");
	}

	@Test
	void 남의_shortCode로_get_comments하면_실패_결과를_돌려준다() {
		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.GET_COMMENTS,
				args().put("shortCode", "THEIRS1"));

		assertThat(result.failed()).isTrue();
		assertThat(result.payloadJson()).doesNotContain("너무 예뻐요");
	}

	@Test
	void 내_게시물은_상세와_댓글이_모두_조회된다() {
		AiToolResult post = toolbox.execute(userId, BrandAiToolSpecs.GET_POST,
				args().put("shortCode", "MINE1"));
		AiToolResult comments = toolbox.execute(userId, BrandAiToolSpecs.GET_COMMENTS,
				args().put("shortCode", "MINE1"));

		assertThat(post.failed()).isFalse();
		assertThat(post.payloadJson()).contains("MINE1 캡션 본문").contains("DISCLOSED");
		assertThat(post.shortCodes()).containsExactly("MINE1");
		assertThat(comments.failed()).isFalse();
		assertThat(comments.payloadJson()).contains("너무 예뻐요");
	}

	@Test
	void 댓글_상한은_모델_요청값과_무관하게_50건으로_잘린다() {
		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.GET_COMMENTS,
				args().put("shortCode", "MINE1").put("limit", 9999));

		assertThat(result.failed()).isFalse();
		assertThat(result.payloadJson()).contains("\"limit\":50");
	}

	@Test
	void 광고_판정_노출_토글이_꺼지면_adDisclosure가_실리지_않는다() {
		BrandAiToolbox hidden = new BrandAiToolbox(linkRepository,
				new BrandReadRepository(JdbcClient.create(dataSource)), objectMapper,
				Clock.fixed(NOW, ZoneOffset.UTC), false);

		AiToolResult result = hidden.execute(userId, BrandAiToolSpecs.GET_POST,
				args().put("shortCode", "MINE1"));

		assertThat(result.payloadJson()).doesNotContain("DISCLOSED");
	}

	@Test
	void 모르는_툴_이름은_실패_결과다() {
		AiToolResult result = toolbox.execute(userId, "drop_table", args());

		assertThat(result.failed()).isTrue();
	}
}
