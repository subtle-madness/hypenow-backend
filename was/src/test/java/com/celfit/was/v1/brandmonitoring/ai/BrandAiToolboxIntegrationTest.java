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

	/** search_posts 전용 픽스처 - 캡션 매칭 대상 게시물. 지표는 검색 테스트와 무관해 고정값으로 둔다. */
	private void insertSearchablePost(long brandId, String shortCode, String author, Instant takenAt,
			String caption) {
		insertMetricPost(brandId, shortCode, author, takenAt, caption, "REELS", 10L, 2L, 100L);
	}

	/**
	 * aggregate_posts 전용 픽스처 - contentType·지표를 자유롭게 지정한다. FEED는 항상 views가 NULL이라는
	 * 서빙 규칙(CLAUDE.md 함정)을 그대로 재현하려고 별도 인서트문을 쓴다(파라미터로 null Long을
	 * 바인딩하는 대신 SQL 리터럴 NULL을 쓴다 - JdbcClient의 타입 미상 null 바인딩을 피한다).
	 */
	private void insertMetricPost(long brandId, String shortCode, String author, Instant takenAt, String caption,
			String contentType, long likes, long comments, Long views) {
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
				VALUES (:shortCode, :author, :contentType, DATE '2026-08-26', :caption, false, 'DISCLOSED')
				""").param("shortCode", shortCode).param("author", author).param("contentType", contentType)
				.param("caption", caption).update();
		if (views == null) {
			monitoringJdbc.sql("""
					INSERT INTO brand_post_snapshot (username, short_code, captured_on, content_type, likes,
					                                  comments, views)
					VALUES (:author, :shortCode, DATE '2026-08-26', :contentType, :likes, :comments, NULL)
					""").param("author", author).param("shortCode", shortCode).param("contentType", contentType)
					.param("likes", likes).param("comments", comments).update();
		} else {
			monitoringJdbc.sql("""
					INSERT INTO brand_post_snapshot (username, short_code, captured_on, content_type, likes,
					                                  comments, views)
					VALUES (:author, :shortCode, DATE '2026-08-26', :contentType, :likes, :comments, :views)
					""").param("author", author).param("shortCode", shortCode).param("contentType", contentType)
					.param("likes", likes).param("comments", comments).param("views", views).update();
		}
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

	/**
	 * 링크 창(collectionMonths)과 모델의 days 필터는 별개 판정이다(N1, 2026-08-28 재리뷰) - direct
	 * 등록은 유저가 명시 등록한 추적 대상이라 표시 창과 무관하게 통과하지만(①), 모델이 "최근 7일"을
	 * 물었으면 2년 전 등록분은 그 답에 섞이면 안 된다(②, 면제 없음). 상세 접근(get_post)은 ①만 타므로
	 * 같은 게시물이 거기선 여전히 조회돼야 한다(FE 상세 화면과 동일 계약).
	 */
	@Test
	void 링크_창_안이지만_days_밖인_direct_게시물은_list_posts에서_빠지고_get_post로는_조회된다() {
		long brandId = insertBrand(monitoringJdbc, "directwindowbrand");
		linkRepository.insertLink(userId, brandId, "directwindowbrand", BrandAccountType.OWN, 12);
		insertDirectOnlyPost(userId, brandId, "OLDDIRECT", "old_direct_author",
				NOW.minusSeconds(730L * 86400));

		AiToolResult list = toolbox.execute(userId, BrandAiToolSpecs.LIST_POSTS,
				args().put("brandId", brandId).put("days", 7));
		AiToolResult post = toolbox.execute(userId, BrandAiToolSpecs.GET_POST,
				args().put("shortCode", "OLDDIRECT"));

		assertThat(list.failed()).isFalse();
		assertThat(list.shortCodes()).doesNotContain("OLDDIRECT");
		assertThat(list.payloadJson()).doesNotContain("OLDDIRECT");
		assertThat(post.failed()).isFalse();
		assertThat(post.shortCodes()).containsExactly("OLDDIRECT");
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

	// ---------- search_posts·aggregate_posts(2026-08-28 데이터 조회 레이어 개선) ----------

	/**
	 * list_posts가 최신 30건·캡션 발췌만 주다가 273건 중 85건 언급을 0건으로 오답한 실측이 배경이다 -
	 * search_posts는 30건 상한과 무관하게 창 안 전체(여기선 40건)에서 정확한 총 매칭 건수(35건)를 낸다.
	 */
	@Test
	void search_posts는_창_안_전체에서_상한_없이_정확한_매칭_건수를_돌려준다() {
		long brandId = insertBrand(monitoringJdbc, "searchbrand");
		linkRepository.insertLink(userId, brandId, "searchbrand", BrandAccountType.OWN, 12);
		for (int i = 1; i <= 35; i++) {
			insertSearchablePost(brandId, "MATCH" + i, "author" + i, NOW.minusSeconds(i * 3600L),
					"신상 세럼 후기 " + i + "번째");
		}
		for (int i = 1; i <= 5; i++) {
			insertSearchablePost(brandId, "NOMATCH" + i, "author" + i, NOW.minusSeconds(i * 3600L), "그냥 일상 게시물");
		}

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.SEARCH_POSTS,
				args().put("brandId", brandId).put("query", "세럼"));

		assertThat(result.failed()).isFalse();
		assertThat(result.payloadJson()).contains("\"totalMatches\":35").contains("\"totalInWindow\":40");
		// 상세 노출은 상위 20건까지만이라도 총 매칭 건수(rowCount)는 35 그대로여야 한다(로그 계약).
		assertThat(result.rowCount()).isEqualTo(35);
	}

	/** 캡션·질의 양쪽 공백을 제거하고 비교한다 - 공백 유무로 갈리는 한글 제품명을 흡수한다. */
	@Test
	void search_posts는_캡션과_질의의_공백_유무를_흡수한다() {
		long brandId = insertBrand(monitoringJdbc, "spacingbrand");
		linkRepository.insertLink(userId, brandId, "spacingbrand", BrandAccountType.OWN, 12);
		insertSearchablePost(brandId, "SPACED1", "author1", NOW.minusSeconds(3600),
				"온그리언츠 바쿠글로우캡슐로션 써봤어요");

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.SEARCH_POSTS,
				args().put("brandId", brandId).put("query", "바쿠 글로우 캡슐"));

		assertThat(result.failed()).isFalse();
		assertThat(result.payloadJson()).contains("\"totalMatches\":1").contains("SPACED1");
	}

	@Test
	void 남의_brandId로_search_posts하면_실패_결과를_돌려준다() {
		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.SEARCH_POSTS,
				args().put("brandId", otherBrandId).put("query", "아무거나"));

		assertThat(result.failed()).isTrue();
		assertThat(result.payloadJson()).contains("접근 권한");
	}

	/** query가 파라미터 바인딩이라 %·'가 섞여도 예외 없이 안전하게 처리된다(SQL 조립 문자열 결합 금지 검증). */
	@Test
	void search_posts는_query에_SQL_메타문자가_섞여도_안전하다() {
		long brandId = insertBrand(monitoringJdbc, "metacharbrand");
		linkRepository.insertLink(userId, brandId, "metacharbrand", BrandAccountType.OWN, 12);
		insertSearchablePost(brandId, "META1", "author1", NOW.minusSeconds(3600), "평범한 캡션입니다");

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.SEARCH_POSTS,
				args().put("brandId", brandId).put("query", "100% 할인' OR '1'='1"));

		assertThat(result.failed()).isFalse();
		assertThat(result.payloadJson()).contains("\"totalMatches\":0");
	}

	/**
	 * 게시물 수·합계·평균 집계(설계 §요구) - 피드는 조회수가 항상 NULL이라(CLAUDE.md 함정) 조회수
	 * 집계·평균은 릴스 2건만 분모에 들어가야 한다(피드 1건은 분모에서 제외). 좋아요·댓글은 수집된 3건
	 * 전부가 분모다. topPost는 조회수가 더 높은 릴스여야 한다.
	 */
	@Test
	void aggregate_posts는_피드_조회수를_제외하고_정확히_집계한다() {
		long brandId = insertBrand(monitoringJdbc, "aggbrand");
		linkRepository.insertLink(userId, brandId, "aggbrand", BrandAccountType.OWN, 12);
		insertMetricPost(brandId, "REEL1", "author1", NOW.minusSeconds(3600), "릴스 하나", "REELS", 10, 2, 1000L);
		insertMetricPost(brandId, "REEL2", "author2", NOW.minusSeconds(7200), "릴스 둘", "REELS", 20, 3, 4000L);
		insertMetricPost(brandId, "FEED1", "author3", NOW.minusSeconds(10800), "피드 하나", "FEED", 5, 1, null);

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.AGGREGATE_POSTS,
				args().put("brandId", brandId));

		assertThat(result.failed()).isFalse();
		String payload = result.payloadJson();
		assertThat(payload).contains("\"postCount\":3").contains("\"reelsCount\":2").contains("\"feedCount\":1");
		assertThat(payload).contains("\"totalViews\":5000").contains("\"viewsSampleCount\":2");
		assertThat(payload).contains("\"avgViews\":2500.0");
		assertThat(payload).contains("\"totalLikes\":35").contains("\"likesSampleCount\":3");
		assertThat(payload).contains("\"totalComments\":6").contains("\"commentsSampleCount\":3");
		assertThat(payload).contains("\"topPost\"").contains("\"shortCode\":\"REEL2\"").contains("\"views\":4000");
	}

	@Test
	void 남의_brandId로_aggregate_posts하면_실패_결과를_돌려준다() {
		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.AGGREGATE_POSTS,
				args().put("brandId", otherBrandId));

		assertThat(result.failed()).isTrue();
		assertThat(result.payloadJson()).contains("접근 권한");
	}
}
