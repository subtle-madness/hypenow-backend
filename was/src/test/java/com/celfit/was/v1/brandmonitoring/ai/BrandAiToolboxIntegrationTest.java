package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandHashtagTagRepository;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandPostCampaignRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.v1.brandmonitoring.BrandAccountType;
import com.celfit.was.v1.brandmonitoring.BrandHashtagPostAssembler;
import com.celfit.was.v1.brandmonitoring.BrandPostAssembler;
import com.celfit.was.v1.monitoring.TrackingItemAssembler;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
 * 툴 레이어 소유 검증 통합 검증(설계 §4·§9, 2026-08-27 격리 계통 재배치 리뷰 C1/I2/I3/I4/I9) - 실 DB
 * 위에서 "남의 brandId·shortCode를 넘기면 막히는가"뿐 아니라 "브랜드 공유 유저 사이의 노출 필터·
 * 표시 창·경쟁사 광고 판정 억제가 FE 표시 표면과 같게 강제되는가"가 필수 케이스다. monitoring
 * 테이블은 was 테스트 픽스처(monitoring-brand-schema.sql)를 같은 컨테이너에 얹어 재현한다
 * (V1BrandDirectPostCancelIntegrationTest와 같은 관용구).
 *
 * <p>{@link BrandPostAssembler}·{@link BrandHashtagPostAssembler}는 monitoring.enabled 조건부 빈이라
 * (이 테스트는 그 프로퍼티를 켜지 않는다) 직접 생성한다 - 협력자(BrandDirectPostRepository·
 * BrandPostCampaignRepository·BrandHashtagTagRepository·MonitoringItemRepository·
 * TrackingItemAssembler)는 전부 무조건부 빈이라 autowire한다({@link BrandReadRepository}만 이 파일이
 * 직접 만드는 monitoringJdbc로 감싼다 - 그 클래스도 monitoring.enabled 조건부다).
 */
class BrandAiToolboxIntegrationTest extends IntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

	@Autowired
	BrandLinkRepository linkRepository;
	@Autowired
	BrandDirectPostRepository directPostRepository;
	@Autowired
	BrandPostCampaignRepository postCampaignRepository;
	@Autowired
	BrandHashtagTagRepository hashtagTagRepository;
	@Autowired
	MonitoringItemRepository monitoringItemRepository;
	@Autowired
	TrackingItemAssembler trackingItemAssembler;
	@Autowired
	DataSource dataSource;
	@Autowired
	JdbcClient jdbcClient;
	@Autowired
	ObjectMapper objectMapper;

	JdbcClient monitoringJdbc;
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
		monitoringJdbc = JdbcClient.create(dataSource);
		monitoringJdbc.sql("""
				TRUNCATE brand_tagged_post, brand_account, brand_post_meta, brand_post_snapshot,
				         brand_post_comment, author_profile, brand_hashtag_post, brand_hashtag_post_matched_tags
				         RESTART IDENTITY CASCADE
				""").update();
		jdbcClient.sql("""
				TRUNCATE app.brand_monitorings, app.brand_direct_posts, app.brand_hashtag_tags
				         RESTART IDENTITY CASCADE
				""").update();

		toolbox = newToolbox(monitoringJdbc, true);

		userId = insertUser();
		otherUserId = insertUser();
		myBrandId = insertBrand(monitoringJdbc, "mybrand");
		otherBrandId = insertBrand(monitoringJdbc, "otherbrand");
		linkRepository.insertLink(userId, myBrandId, "mybrand", BrandAccountType.OWN, 12);
		linkRepository.insertLink(otherUserId, otherBrandId, "otherbrand", BrandAccountType.OWN, 12);

		insertPost(monitoringJdbc, myBrandId, "MINE1", "mine_author");
		insertPost(monitoringJdbc, otherBrandId, "THEIRS1", "their_author");
	}

	/**
	 * 소유 검증 대상 조립기 - {@link BrandAiConfig#brandAiToolbox}와 같은 배선을 재현한다. exposeAd가
	 * false면(광고 판정 노출 토글 끔 케이스) BrandPostAssembler 쪽에 그 값을 흘려보낸다.
	 */
	private BrandAiToolbox newToolbox(JdbcClient monitoringJdbc, boolean exposeAdDisclosure) {
		BrandReadRepository brandReadRepository = new BrandReadRepository(monitoringJdbc);
		BrandPostAssembler postAssembler = new BrandPostAssembler(brandReadRepository, postCampaignRepository,
				directPostRepository, trackingItemAssembler, monitoringItemRepository, exposeAdDisclosure);
		BrandHashtagPostAssembler hashtagPostAssembler = new BrandHashtagPostAssembler(brandReadRepository,
				directPostRepository, hashtagTagRepository);
		return new BrandAiToolbox(linkRepository, brandReadRepository, postAssembler, hashtagPostAssembler,
				objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
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
		insertTaggedPost(monitoringJdbc, brandId, shortCode, author, NOW.minusSeconds(86400));
	}

	/**
	 * 태그 감지 게시물(브랜드 공유 — 전원 노출) — takenAt을 지정해 표시 창(collectionMonths) 테스트에
	 * 쓴다. enriched_at을 명시적으로 채운다 - {@code BrandPostAssembler#indexForBrand}가 표시 표면
	 * 계약대로 ENRICHED_ONLY 고정 조회라(I3), 정산 전 게시물은 DEFAULT NULL로 애초에 인덱스에 안 잡힌다.
	 */
	private void insertTaggedPost(JdbcClient monitoringJdbc, long brandId, String shortCode, String author,
			Instant takenAt) {
		monitoringJdbc.sql("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, author_ig_user_id, taken_at,
				                                enriched_at)
				VALUES (:brandId, :shortCode, :author, :author, :takenAt, now())
				""").param("brandId", brandId).param("shortCode", shortCode).param("author", author)
				.param("takenAt", OffsetDateTime.ofInstant(takenAt, ZoneOffset.UTC))
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

	/**
	 * direct-only 등록(해시태그 미감지, C1) - tag_detected_at을 명시적으로 NULL로 덮어 DEFAULT now()를
	 * 무력화한다. app.brand_direct_posts 원장까지 같이 채워야 {@code indexForBrand}의 노출 필터가
	 * "등록자"로 인식한다({@link BrandDirectPostRepository#upsertDirect}).
	 */
	private void insertDirectOnlyPost(long registeredByUserId, long brandId, String shortCode, String author,
			Instant takenAt) {
		monitoringJdbc.sql("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, author_ig_user_id, taken_at,
				                                tag_detected_at, direct_registered_at, enriched_at)
				VALUES (:brandId, :shortCode, :author, :author, :takenAt, NULL, :takenAt, now())
				""").param("brandId", brandId).param("shortCode", shortCode).param("author", author)
				.param("takenAt", OffsetDateTime.ofInstant(takenAt, ZoneOffset.UTC))
				.update();
		directPostRepository.upsertDirect(registeredByUserId, brandId, shortCode);
	}

	private void insertHashtagPost(long brandId, String shortCode, String matchedTag, String author) {
		monitoringJdbc.sql("""
				INSERT INTO brand_hashtag_post (brand_id, short_code, matched_tag, author_username, taken_at,
				                                verdict, verdict_source)
				VALUES (:brandId, :shortCode, :matchedTag, :author, :takenAt, 'RELEVANT', 'RULE')
				""").param("brandId", brandId).param("shortCode", shortCode).param("matchedTag", matchedTag)
				.param("author", author)
				.param("takenAt", OffsetDateTime.ofInstant(NOW.minusSeconds(86400), ZoneOffset.UTC))
				.update();
		monitoringJdbc.sql("""
				INSERT INTO brand_hashtag_post_matched_tags (brand_id, short_code, tag)
				VALUES (:brandId, :shortCode, :tag)
				""").param("brandId", brandId).param("shortCode", shortCode).param("tag", matchedTag).update();
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
		BrandAiToolbox hidden = newToolbox(monitoringJdbc, false);

		AiToolResult result = hidden.execute(userId, BrandAiToolSpecs.GET_POST,
				args().put("shortCode", "MINE1"));

		assertThat(result.payloadJson()).doesNotContain("DISCLOSED");
	}

	@Test
	void 모르는_툴_이름은_실패_결과다() {
		AiToolResult result = toolbox.execute(userId, "drop_table", args());

		assertThat(result.failed()).isTrue();
	}

	// ---------- 격리 계통 재배치(2026-08-27 리뷰 C1/I2/I3/I4/I9) ----------

	@Test
	void B가_direct_only로_등록한_게시물은_A의_list_posts에_안_나온다() {
		long sharedBrandId = insertBrand(monitoringJdbc, "sharedbrand");
		linkRepository.insertLink(userId, sharedBrandId, "sharedbrand", BrandAccountType.OWN, 12);
		linkRepository.insertLink(otherUserId, sharedBrandId, "sharedbrand", BrandAccountType.OWN, 12);
		insertDirectOnlyPost(otherUserId, sharedBrandId, "BONLY1", "b_author", NOW.minusSeconds(86400));

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.LIST_POSTS,
				args().put("brandId", sharedBrandId));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).doesNotContain("BONLY1");
		assertThat(result.payloadJson()).doesNotContain("BONLY1");
	}

	@Test
	void A가_B의_direct_only_shortCode로_get_post_get_comments하면_실패_결과다() {
		long sharedBrandId = insertBrand(monitoringJdbc, "sharedbrand2");
		linkRepository.insertLink(userId, sharedBrandId, "sharedbrand2", BrandAccountType.OWN, 12);
		linkRepository.insertLink(otherUserId, sharedBrandId, "sharedbrand2", BrandAccountType.OWN, 12);
		insertDirectOnlyPost(otherUserId, sharedBrandId, "BONLY2", "b_author2", NOW.minusSeconds(86400));

		AiToolResult post = toolbox.execute(userId, BrandAiToolSpecs.GET_POST, args().put("shortCode", "BONLY2"));
		AiToolResult comments = toolbox.execute(userId, BrandAiToolSpecs.GET_COMMENTS,
				args().put("shortCode", "BONLY2"));

		assertThat(post.failed()).isTrue();
		assertThat(comments.failed()).isTrue();
	}

	@Test
	void 표시_창_밖_게시물은_list_posts와_get_post에서_모두_막힌다() {
		long windowBrandId = insertBrand(monitoringJdbc, "windowbrand");
		// 1개월 표시 창 - 실측 "오늘"(NOW) 기준 200일 전 게시물은 창 밖이다.
		linkRepository.insertLink(userId, windowBrandId, "windowbrand", BrandAccountType.OWN, 1);
		insertTaggedPost(monitoringJdbc, windowBrandId, "OLDPOST", "old_author",
				NOW.minusSeconds(200L * 86400));

		AiToolResult list = toolbox.execute(userId, BrandAiToolSpecs.LIST_POSTS,
				args().put("brandId", windowBrandId).put("days", 365));
		AiToolResult post = toolbox.execute(userId, BrandAiToolSpecs.GET_POST, args().put("shortCode", "OLDPOST"));

		assertThat(list.failed()).isFalse();
		assertThat(list.shortCodes()).doesNotContain("OLDPOST");
		assertThat(post.failed()).isTrue();
	}

	@Test
	void 경쟁사_브랜드의_get_post에는_adDisclosure가_실리지_않는다() {
		long competitorBrandId = insertBrand(monitoringJdbc, "competitorbrand");
		linkRepository.insertLink(userId, competitorBrandId, "competitorbrand", BrandAccountType.COMPETITOR, 12);
		insertPost(monitoringJdbc, competitorBrandId, "COMP1", "comp_author");

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.GET_POST, args().put("shortCode", "COMP1"));

		assertThat(result.failed()).isFalse();
		assertThat(result.payloadJson()).doesNotContain("DISCLOSED");
	}

	@Test
	void B만_등록한_해시태그로_매칭된_게시물은_A의_목록에_안_나온다() {
		long hashtagBrandId = insertBrand(monitoringJdbc, "hashtagbrand");
		linkRepository.insertLink(userId, hashtagBrandId, "hashtagbrand", BrandAccountType.OWN, 12);
		linkRepository.insertLink(otherUserId, hashtagBrandId, "hashtagbrand", BrandAccountType.OWN, 12);
		// A 본인의 태그 원장을 비워두면 필터 자체가 스킵된다(시딩 전 정합성, fail-open) - 이 케이스가
		// 실제로 필터링을 검증하려면 A도 자기 태그를 갖고 있어야 한다(B의 태그와 교집합이 없게).
		hashtagTagRepository.addTags(userId, hashtagBrandId, List.of("#other"));
		hashtagTagRepository.addTags(otherUserId, hashtagBrandId, List.of("#special"));
		insertHashtagPost(hashtagBrandId, "HTAG1", "#special", "hashtag_author");

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.LIST_HASHTAG_POSTS,
				args().put("brandId", hashtagBrandId));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).doesNotContain("HTAG1");
		assertThat(result.payloadJson()).doesNotContain("HTAG1");
	}
}
