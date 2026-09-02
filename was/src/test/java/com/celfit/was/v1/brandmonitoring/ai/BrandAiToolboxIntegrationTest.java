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
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.monitoring.TrackingItemAssembler;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
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
				directPostRepository, trackingItemAssembler, monitoringItemRepository, hashtagTagRepository,
				exposeAdDisclosure);
		BrandHashtagPostAssembler hashtagPostAssembler = new BrandHashtagPostAssembler(postAssembler,
				brandReadRepository);
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
		insertPostSkeleton(monitoringJdbc, brandId, shortCode, author, takenAt);
		insertComment(monitoringJdbc, shortCode, shortCode + "-c1", "fan1", "너무 예뻐요", 3, NOW);
	}

	/** {@link #insertTaggedPost}에서 댓글 삽입만 뺀 골격 - get_comments 배치화 테스트(다건 댓글 시드)가 재사용한다. */
	private void insertPostSkeleton(JdbcClient monitoringJdbc, long brandId, String shortCode, String author,
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
	}

	private void insertComment(JdbcClient monitoringJdbc, String shortCode, String id, String author, String body,
			long likeCount, Instant at) {
		monitoringJdbc.sql("""
				INSERT INTO brand_post_comment (short_code, id, author, body, like_count, commented_at)
				VALUES (:shortCode, :id, :author, :body, :likeCount, :at)
				""").param("shortCode", shortCode).param("id", id).param("author", author).param("body", body)
				.param("likeCount", likeCount).param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).update();
	}

	/**
	 * get_comments 배치화 테스트 전용(2026-08-31, 스펙 §3-3) - 골격(insertPostSkeleton)만 태우고 댓글을
	 * commentCount건 시드한다. commentedAt을 댓글마다 다르게 둬 최신순 정렬이 실제로 의미 있게 한다.
	 */
	private void insertPostWithComments(long brandId, String shortCode, String author, int commentCount) {
		insertPostSkeleton(monitoringJdbc, brandId, shortCode, author, NOW.minusSeconds(86400));
		for (int i = 1; i <= commentCount; i++) {
			insertComment(monitoringJdbc, shortCode, shortCode + "-c" + i, "fan" + i, "댓글 " + i, i,
					NOW.minusSeconds(i));
		}
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
		// 해시태그 직접 수집 전환(2026-08-27 설계) — 발견 게시물은 별도 테이블이 아니라 통합 풀
		// (brand_tagged_post, hashtag_detected_at만 non-null)에 산다. 매칭 태그는 brand_post_matched_tag.
		monitoringJdbc.sql("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, author_ig_user_id,
				                               taken_at, enriched_at, tag_detected_at, hashtag_detected_at)
				VALUES (:brandId, :shortCode, :author, :author, :takenAt, now(), NULL, :takenAt)
				""").param("brandId", brandId).param("shortCode", shortCode).param("author", author)
				.param("takenAt", OffsetDateTime.ofInstant(NOW.minusSeconds(86400), ZoneOffset.UTC))
				.update();
		monitoringJdbc.sql("""
				INSERT INTO brand_post_meta (short_code, username, content_type, uploaded_at, caption)
				VALUES (:shortCode, :author, 'reel', DATE '2026-08-26', '#해시태그 발견 게시물')
				ON CONFLICT (short_code) DO NOTHING
				""").param("shortCode", shortCode).param("author", author).update();
		monitoringJdbc.sql("""
				INSERT INTO brand_post_matched_tag (brand_id, short_code, tag)
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

	/** author_profile 1행 - scope의 작성자 검색(q)·팔로워 필터(T3) 테스트 전용. */
	private void insertAuthorProfile(String igUserId, String username, String fullName, Long followers) {
		monitoringJdbc.sql("""
				INSERT INTO author_profile (ig_user_id, username, full_name, followers, fetched_at)
				VALUES (:igUserId, :username, :fullName, :followers, now())
				""").param("igUserId", igUserId).param("username", username).param("fullName", fullName)
				.param("followers", followers).update();
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

	// ---------- days 미지정 = 수집 기간 전체(2026-08-28 기간 기본값 수정) ----------

	/**
	 * days를 넘기지 않은 질문의 자연스러운 의미는 "수집된 전체"다(설계 배경 - 기간 미지정 질문에
	 * 모델이 기본 30일로 답한 실측 오답). 60일 전 게시물은 옛 기본값(30일) 밖이라 이전 동작이면
	 * 매칭에서 빠졌을 것이다 - days 생략 시에도 매칭돼야 한다.
	 */
	@Test
	void search_posts는_days_미지정이면_30일_밖_게시물도_매칭한다() {
		long brandId = insertBrand(monitoringJdbc, "nodaysbrand");
		linkRepository.insertLink(userId, brandId, "nodaysbrand", BrandAccountType.OWN, 12);
		insertSearchablePost(brandId, "OLD60", "author1", NOW.minusSeconds(60L * 86400), "신상 세럼 후기");

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.SEARCH_POSTS,
				args().put("brandId", brandId).put("query", "세럼"));

		assertThat(result.failed()).isFalse();
		assertThat(result.payloadJson()).contains("\"totalMatches\":1").contains("\"window\":\"collection_window\"")
				.contains("OLD60");
	}

	/** days=7을 명시하면 기존과 동일하게 7일 밖 매칭은 빠진다(기존 케이스 유지 확인). */
	@Test
	void search_posts는_days_지정_시_그_기간_밖_매칭은_제외한다() {
		long brandId = insertBrand(monitoringJdbc, "dayssevenbrand");
		linkRepository.insertLink(userId, brandId, "dayssevenbrand", BrandAccountType.OWN, 12);
		insertSearchablePost(brandId, "RECENT1", "author1", NOW.minusSeconds(3600), "신상 세럼 후기");
		insertSearchablePost(brandId, "OLD60", "author2", NOW.minusSeconds(60L * 86400), "신상 세럼 후기");

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.SEARCH_POSTS,
				args().put("brandId", brandId).put("query", "세럼").put("days", 7));

		assertThat(result.failed()).isFalse();
		assertThat(result.payloadJson()).contains("\"totalMatches\":1").contains("\"window\":\"days_filter\"")
				.contains("RECENT1").doesNotContain("OLD60");
	}

	/** aggregate_posts의 postCount도 days 미지정이면 창 전체(link window) 건수와 일치해야 한다. */
	@Test
	void aggregate_posts는_days_미지정이면_postCount가_창_전체_건수와_일치한다() {
		long brandId = insertBrand(monitoringJdbc, "nodaysaggbrand");
		linkRepository.insertLink(userId, brandId, "nodaysaggbrand", BrandAccountType.OWN, 12);
		insertMetricPost(brandId, "RECENT1", "author1", NOW.minusSeconds(3600), "최근 릴스", "REELS", 10, 2, 1000L);
		insertMetricPost(brandId, "OLD60", "author2", NOW.minusSeconds(60L * 86400), "옛날 릴스", "REELS", 5, 1, 500L);

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.AGGREGATE_POSTS,
				args().put("brandId", brandId));

		assertThat(result.failed()).isFalse();
		assertThat(result.payloadJson()).contains("\"postCount\":2").contains("\"window\":\"collection_window\"");
		assertThat(result.rowCount()).isEqualTo(2);
	}

	/** list_posts도 같은 공통 창 로직(resolveWindow)을 타므로 days 미지정 시 30일 밖 게시물이 보여야 한다. */
	@Test
	void list_posts는_days_미지정이면_30일_밖_게시물도_돌려준다() {
		long brandId = insertBrand(monitoringJdbc, "nodayslistbrand");
		linkRepository.insertLink(userId, brandId, "nodayslistbrand", BrandAccountType.OWN, 12);
		insertTaggedPost(monitoringJdbc, brandId, "OLD60LIST", "author1", NOW.minusSeconds(60L * 86400));

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.LIST_POSTS, args().put("brandId", brandId));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).contains("OLD60LIST");
		assertThat(result.payloadJson()).contains("\"window\":\"collection_window\"");
	}

	// ---------- FE scope 강제(T3, 2026-08-28 FE 변경요청서) ----------

	@Test
	void scope_날짜_필터는_범위_밖_게시물을_제외한다() {
		long brandId = insertBrand(monitoringJdbc, "scopedatebrand");
		linkRepository.insertLink(userId, brandId, "scopedatebrand", BrandAccountType.OWN, 12);
		insertTaggedPost(monitoringJdbc, brandId, "INRANGE", "author1", NOW.minusSeconds(3L * 86400));
		insertTaggedPost(monitoringJdbc, brandId, "OUTRANGE", "author2", NOW.minusSeconds(30L * 86400));
		AiScope scope = new AiScope(java.time.LocalDate.of(2026, 8, 20), java.time.LocalDate.of(2026, 8, 27),
				null, null, null, null, null, null);

		AiToolResult result = toolbox.execute(new BrandAiToolbox.ToolSession(scope), userId,
				BrandAiToolSpecs.LIST_POSTS, args().put("brandId", brandId));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).contains("INRANGE").doesNotContain("OUTRANGE");
	}

	@Test
	void scope_mediaType_필터는_릴스만_남긴다() {
		long brandId = insertBrand(monitoringJdbc, "scopemediabrand");
		linkRepository.insertLink(userId, brandId, "scopemediabrand", BrandAccountType.OWN, 12);
		insertMetricPost(brandId, "REELPOST", "author1", NOW.minusSeconds(3600), "릴스", "REELS", 1, 1, 100L);
		insertMetricPost(brandId, "FEEDPOST", "author2", NOW.minusSeconds(3600), "피드", "FEED", 1, 1, null);
		AiScope scope = new AiScope(null, null, "reels", null, null, null, null, null);

		AiToolResult result = toolbox.execute(new BrandAiToolbox.ToolSession(scope), userId,
				BrandAiToolSpecs.AGGREGATE_POSTS, args().put("brandId", brandId));

		assertThat(result.failed()).isFalse();
		assertThat(result.payloadJson()).contains("\"postCount\":1").contains("\"reelsCount\":1")
				.contains("\"feedCount\":0");
	}

	@Test
	void scope_source_필터는_direct만_남긴다() {
		long brandId = insertBrand(monitoringJdbc, "scopesourcebrand");
		linkRepository.insertLink(userId, brandId, "scopesourcebrand", BrandAccountType.OWN, 12);
		insertTaggedPost(monitoringJdbc, brandId, "TAGGEDONE", "author1", NOW.minusSeconds(3600));
		insertDirectOnlyPost(userId, brandId, "DIRECTONE", "author2", NOW.minusSeconds(3600));
		AiScope scope = new AiScope(null, null, null, null, "direct", null, null, null);

		AiToolResult result = toolbox.execute(new BrandAiToolbox.ToolSession(scope), userId,
				BrandAiToolSpecs.LIST_POSTS, args().put("brandId", brandId));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).containsExactly("DIRECTONE");
	}

	@Test
	void scope_sponsorship_필터는_광고_표기만_남긴다() {
		long brandId = insertBrand(monitoringJdbc, "sponsorshipbrand");
		linkRepository.insertLink(userId, brandId, "sponsorshipbrand", BrandAccountType.OWN, 12);
		insertSearchablePost(brandId, "ADPOST", "author1", NOW.minusSeconds(3600), "#광고 협찬 받았어요");
		insertSearchablePost(brandId, "ORGANICPOST", "author2", NOW.minusSeconds(3600), "그냥 일상 게시물");
		AiScope scope = new AiScope(null, null, null, "sponsored", null, null, null, null);

		AiToolResult result = toolbox.execute(new BrandAiToolbox.ToolSession(scope), userId,
				BrandAiToolSpecs.LIST_POSTS, args().put("brandId", brandId));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).containsExactly("ADPOST");
	}

	@Test
	void scope_q_필터는_작성자_계정명_부분일치로_좁힌다() {
		long brandId = insertBrand(monitoringJdbc, "scopeqbrand");
		linkRepository.insertLink(userId, brandId, "scopeqbrand", BrandAccountType.OWN, 12);
		insertTaggedPost(monitoringJdbc, brandId, "GLOWPOST", "glow_official", NOW.minusSeconds(3600));
		insertTaggedPost(monitoringJdbc, brandId, "OTHERPOST", "random_user", NOW.minusSeconds(3600));
		insertAuthorProfile("glow_official", "glow_official", "글로우 공식", 5000L);
		insertAuthorProfile("random_user", "random_user", "랜덤유저", 5000L);
		AiScope scope = new AiScope(null, null, null, null, null, null, null, "glow");

		AiToolResult result = toolbox.execute(new BrandAiToolbox.ToolSession(scope), userId,
				BrandAiToolSpecs.LIST_POSTS, args().put("brandId", brandId));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).containsExactly("GLOWPOST");
	}

	@Test
	void scope_팔로워_범위_필터는_범위_밖_작성자를_제외한다() {
		long brandId = insertBrand(monitoringJdbc, "scopefollowerbrand");
		linkRepository.insertLink(userId, brandId, "scopefollowerbrand", BrandAccountType.OWN, 12);
		insertTaggedPost(monitoringJdbc, brandId, "BIGPOST", "big_author", NOW.minusSeconds(3600));
		insertTaggedPost(monitoringJdbc, brandId, "SMALLPOST", "small_author", NOW.minusSeconds(7200));
		insertAuthorProfile("big_author", "big_author", "빅", 100_000L);
		insertAuthorProfile("small_author", "small_author", "스몰", 500L);
		AiScope scope = new AiScope(null, null, null, null, null, 10_000, null, null);

		AiToolResult result = toolbox.execute(new BrandAiToolbox.ToolSession(scope), userId,
				BrandAiToolSpecs.LIST_POSTS, args().put("brandId", brandId));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).containsExactly("BIGPOST");
	}

	/** 작성자 프로필이 아예 수집되지 않은 게시물은 q·팔로워 필터가 걸려 있으면 제외한다(설계 §요구). */
	@Test
	void scope_작성자_필터는_프로필_미수집_게시물을_제외한다() {
		long brandId = insertBrand(monitoringJdbc, "scopenoprofilebrand");
		linkRepository.insertLink(userId, brandId, "scopenoprofilebrand", BrandAccountType.OWN, 12);
		insertTaggedPost(monitoringJdbc, brandId, "NOPROFILE", "unknown_author", NOW.minusSeconds(3600));
		AiScope scope = new AiScope(null, null, null, null, null, 0, null, null);

		AiToolResult result = toolbox.execute(new BrandAiToolbox.ToolSession(scope), userId,
				BrandAiToolSpecs.LIST_POSTS, args().put("brandId", brandId));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).doesNotContain("NOPROFILE");
	}

	@Test
	void scope_밖_shortCode로_get_post하면_실패_결과다() {
		long brandId = insertBrand(monitoringJdbc, "scopegetpostbrand");
		linkRepository.insertLink(userId, brandId, "scopegetpostbrand", BrandAccountType.OWN, 12);
		insertMetricPost(brandId, "FEEDONLY", "author1", NOW.minusSeconds(3600), "피드", "FEED", 1, 1, null);
		AiScope scope = new AiScope(null, null, "reels", null, null, null, null, null);

		AiToolResult result = toolbox.execute(new BrandAiToolbox.ToolSession(scope), userId,
				BrandAiToolSpecs.GET_POST, args().put("shortCode", "FEEDONLY"));

		assertThat(result.failed()).isTrue();
	}

	/** scope 날짜는 링크 창(collectionMonths)과 교집합이다 - scope가 더 넓어도 링크 창 밖은 여전히 막힌다. */
	@Test
	void scope_날짜가_링크_창보다_넓어도_링크_창_밖_게시물은_안_나온다() {
		long brandId = insertBrand(monitoringJdbc, "scopewindowbrand");
		// 1개월 표시 창
		linkRepository.insertLink(userId, brandId, "scopewindowbrand", BrandAccountType.OWN, 1);
		insertTaggedPost(monitoringJdbc, brandId, "FARPAST", "author1", NOW.minusSeconds(200L * 86400));
		// scope는 아주 넓게(1년 전부터) 잡아도 링크 창(1개월)이 여전히 더 좁은 제약이다.
		AiScope scope = new AiScope(java.time.LocalDate.of(2025, 1, 1), null, null, null, null, null, null, null);

		AiToolResult result = toolbox.execute(new BrandAiToolbox.ToolSession(scope), userId,
				BrandAiToolSpecs.LIST_POSTS, args().put("brandId", brandId));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).doesNotContain("FARPAST");
	}

	@Test
	void list_hashtag_posts는_scope_날짜만_적용한다() {
		long brandId = insertBrand(monitoringJdbc, "scopehashtagbrand");
		linkRepository.insertLink(userId, brandId, "scopehashtagbrand", BrandAccountType.OWN, 12);
		// insertHashtagPost는 항상 NOW-1일에 심는다(고정 helper) - scope.dateFrom을 그보다 미래로 잡아
		// 게시물이 scope 날짜 필터 하나만으로 걸러지는지 본다(장부 태그는 있어 격리 필터는 통과 전제).
		hashtagTagRepository.addTags(userId, brandId, List.of("#brand"));
		insertHashtagPost(brandId, "HTAGIN", "#brand", "author1");
		LocalDate afterSeed = KstTimestamps.toKstDate(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)).plusDays(1);
		AiScope scope = new AiScope(afterSeed, null, null, null, null, null, null, null);

		AiToolResult result = toolbox.execute(new BrandAiToolbox.ToolSession(scope), userId,
				BrandAiToolSpecs.LIST_HASHTAG_POSTS, args().put("brandId", brandId));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).doesNotContain("HTAGIN");
	}

	/** references 라벨 조립(T7) 지원 - list_posts를 한 번 태우면 세션 캐시에서 shortCode로 PostRef를 찾을 수 있어야 한다. */
	@Test
	void 세션은_list_posts_이후_findCachedRef로_PostRef를_찾을_수_있다() {
		long brandId = insertBrand(monitoringJdbc, "cachedrefbrand");
		linkRepository.insertLink(userId, brandId, "cachedrefbrand", BrandAccountType.OWN, 12);
		insertPost(monitoringJdbc, brandId, "CACHEME", "cache_author");
		BrandAiToolbox.ToolSession session = new BrandAiToolbox.ToolSession(null);

		toolbox.execute(session, userId, BrandAiToolSpecs.LIST_POSTS, args().put("brandId", brandId));

		var ref = session.findCachedRef("CACHEME");
		assertThat(ref).isPresent();
		assertThat(ref.get().authorUsername()).isEqualTo("cache_author");
	}

	// ---------- F6(2026-08-30 리뷰) 해시태그 q·팔로워 필터 ----------

	@Test
	void list_hashtag_posts는_scope_q_필터로_작성자를_좁힌다() {
		long brandId = insertBrand(monitoringJdbc, "hashtagqbrand");
		linkRepository.insertLink(userId, brandId, "hashtagqbrand", BrandAccountType.OWN, 12);
		// 격리 필터 fail-open 폐기(2026-08-27 설계 §4) — 조회자 장부 태그와 교집합이 있어야 노출된다.
		hashtagTagRepository.addTags(userId, brandId, List.of("#brand"));
		insertHashtagPost(brandId, "HQ1", "#brand", "glow_official");
		insertHashtagPost(brandId, "HQ2", "#brand", "random_user");
		insertAuthorProfile("glow_official", "glow_official", "글로우 공식", 5000L);
		insertAuthorProfile("random_user", "random_user", "랜덤유저", 5000L);
		AiScope scope = new AiScope(null, null, null, null, null, null, null, "glow");

		AiToolResult result = toolbox.execute(new BrandAiToolbox.ToolSession(scope), userId,
				BrandAiToolSpecs.LIST_HASHTAG_POSTS, args().put("brandId", brandId));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).containsExactly("HQ1");
	}

	@Test
	void list_hashtag_posts는_scope_팔로워_범위_필터로_좁힌다() {
		long brandId = insertBrand(monitoringJdbc, "hashtagfollowerbrand");
		linkRepository.insertLink(userId, brandId, "hashtagfollowerbrand", BrandAccountType.OWN, 12);
		hashtagTagRepository.addTags(userId, brandId, List.of("#brand"));
		insertHashtagPost(brandId, "HF1", "#brand", "big_author");
		insertHashtagPost(brandId, "HF2", "#brand", "small_author");
		insertAuthorProfile("big_author", "big_author", "빅", 100_000L);
		insertAuthorProfile("small_author", "small_author", "스몰", 500L);
		AiScope scope = new AiScope(null, null, null, null, null, 10_000, null, null);

		AiToolResult result = toolbox.execute(new BrandAiToolbox.ToolSession(scope), userId,
				BrandAiToolSpecs.LIST_HASHTAG_POSTS, args().put("brandId", brandId));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).containsExactly("HF1");
	}

	// ---------- F5(2026-08-30 리뷰) scope 대소문자 정규화 ----------

	/** FE가 대문자로 보내도(예: "DIRECT") AiScope.from()이 소문자로 정규화해 source 필터가 정상 동작한다. */
	@Test
	void scope_source_필터는_대문자_입력도_소문자로_정규화되어_정상_동작한다() {
		long brandId = insertBrand(monitoringJdbc, "scopeuppercasebrand");
		linkRepository.insertLink(userId, brandId, "scopeuppercasebrand", BrandAccountType.OWN, 12);
		insertTaggedPost(monitoringJdbc, brandId, "TAGGEDUP", "author1", NOW.minusSeconds(3600));
		insertDirectOnlyPost(userId, brandId, "DIRECTUP", "author2", NOW.minusSeconds(3600));
		AiScope scope = AiScope.from(
				new AiMessagesRequest.ScopeRequest(null, null, null, null, "DIRECT", null, null, null));

		AiToolResult result = toolbox.execute(new BrandAiToolbox.ToolSession(scope), userId,
				BrandAiToolSpecs.LIST_POSTS, args().put("brandId", brandId));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).containsExactly("DIRECTUP");
	}

	// ---------- F1(2026-08-30 리뷰) 세션 brandId 강제 ----------

	@Test
	void 세션에_고정된_brandId와_다른_소유_브랜드로_list_posts하면_실패_결과다() {
		long secondBrandId = insertBrand(monitoringJdbc, "secondownedbrand");
		linkRepository.insertLink(userId, secondBrandId, "secondownedbrand", BrandAccountType.OWN, 12);
		insertPost(monitoringJdbc, secondBrandId, "SECOND1", "second_author");
		// 세션은 myBrandId로 고정 - 유저가 secondBrandId도 소유하고 있지만 이 대화 범위 밖이다.
		BrandAiToolbox.ToolSession session = new BrandAiToolbox.ToolSession(null, myBrandId);

		AiToolResult result = toolbox.execute(session, userId, BrandAiToolSpecs.LIST_POSTS,
				args().put("brandId", secondBrandId));

		assertThat(result.failed()).isTrue();
		assertThat(result.shortCodes()).isEmpty();
	}

	@Test
	void 세션에_고정된_brandId와_다른_소유_브랜드로_search_posts하면_실패_결과다() {
		long secondBrandId = insertBrand(monitoringJdbc, "secondownedsearchbrand");
		linkRepository.insertLink(userId, secondBrandId, "secondownedsearchbrand", BrandAccountType.OWN, 12);
		BrandAiToolbox.ToolSession session = new BrandAiToolbox.ToolSession(null, myBrandId);

		AiToolResult result = toolbox.execute(session, userId, BrandAiToolSpecs.SEARCH_POSTS,
				args().put("brandId", secondBrandId).put("query", "아무거나"));

		assertThat(result.failed()).isTrue();
	}

	@Test
	void 세션에_고정된_brandId와_다른_소유_브랜드로_aggregate_posts하면_실패_결과다() {
		long secondBrandId = insertBrand(monitoringJdbc, "secondownedaggbrand");
		linkRepository.insertLink(userId, secondBrandId, "secondownedaggbrand", BrandAccountType.OWN, 12);
		BrandAiToolbox.ToolSession session = new BrandAiToolbox.ToolSession(null, myBrandId);

		AiToolResult result = toolbox.execute(session, userId, BrandAiToolSpecs.AGGREGATE_POSTS,
				args().put("brandId", secondBrandId));

		assertThat(result.failed()).isTrue();
	}

	@Test
	void 세션에_고정된_brandId와_다른_소유_브랜드로_list_hashtag_posts하면_실패_결과다() {
		long secondBrandId = insertBrand(monitoringJdbc, "secondownedhashtagbrand");
		linkRepository.insertLink(userId, secondBrandId, "secondownedhashtagbrand", BrandAccountType.OWN, 12);
		BrandAiToolbox.ToolSession session = new BrandAiToolbox.ToolSession(null, myBrandId);

		AiToolResult result = toolbox.execute(session, userId, BrandAiToolSpecs.LIST_HASHTAG_POSTS,
				args().put("brandId", secondBrandId));

		assertThat(result.failed()).isTrue();
	}

	@Test
	void 세션에_고정된_brandId와_다른_소유_브랜드의_shortCode로_get_post하면_실패_결과다() {
		long secondBrandId = insertBrand(monitoringJdbc, "secondownedgetpostbrand");
		linkRepository.insertLink(userId, secondBrandId, "secondownedgetpostbrand", BrandAccountType.OWN, 12);
		insertPost(monitoringJdbc, secondBrandId, "SECONDPOST1", "second_author2");
		BrandAiToolbox.ToolSession session = new BrandAiToolbox.ToolSession(null, myBrandId);

		AiToolResult result = toolbox.execute(session, userId, BrandAiToolSpecs.GET_POST,
				args().put("shortCode", "SECONDPOST1"));

		assertThat(result.failed()).isTrue();
	}

	@Test
	void 세션에_brandId가_고정되면_list_brands는_그_브랜드_1건만_돌려준다() {
		long secondBrandId = insertBrand(monitoringJdbc, "secondbrandforlist");
		linkRepository.insertLink(userId, secondBrandId, "secondbrandforlist", BrandAccountType.OWN, 12);
		BrandAiToolbox.ToolSession session = new BrandAiToolbox.ToolSession(null, myBrandId);

		AiToolResult result = toolbox.execute(session, userId, BrandAiToolSpecs.LIST_BRANDS, args());

		assertThat(result.failed()).isFalse();
		assertThat(result.rowCount()).isEqualTo(1);
		assertThat(result.payloadJson()).contains("mybrand").doesNotContain("secondbrandforlist");
	}

	/** 세션 brandId와 요청 brandId가 같으면 정상 통과한다(회귀 방지 - scopeMismatch가 정상 케이스까지 막지 않는지). */
	@Test
	void 세션_brandId와_요청_brandId가_같으면_정상_통과한다() {
		BrandAiToolbox.ToolSession session = new BrandAiToolbox.ToolSession(null, myBrandId);

		AiToolResult result = toolbox.execute(session, userId, BrandAiToolSpecs.LIST_POSTS,
				args().put("brandId", myBrandId));

		assertThat(result.failed()).isFalse();
	}

	// ---------- aggregate_posts 일반화(2026-08-31 툴·한계 재설계, 스펙 §3-1·§3-2) ----------

	/**
	 * 작성자별 집계·파생지표(스펙 §3-2) - author_a는 릴스 2건(조회수 1000·3000, 평균 2000) 팔로워
	 * 100이라 reachMultiple=20.0, author_b는 릴스 1건(조회수 9000) 팔로워 9000이라 reachMultiple=1.0.
	 * orderBy=reachMultiple이면 모델 암산이 아니라 서버 정렬로 author_a가 앞이어야 한다.
	 */
	@Test
	void aggregate_posts_groupBy_author는_작성자별_집계와_파생지표를_서버가_계산해_정렬한다() throws Exception {
		long brandId = insertBrand(monitoringJdbc, "groupauthorbrand");
		linkRepository.insertLink(userId, brandId, "groupauthorbrand", BrandAccountType.OWN, 12);
		insertMetricPost(brandId, "AREEL1", "author_a", NOW.minusSeconds(3600), "a1", "REELS", 1, 1, 1000L);
		insertMetricPost(brandId, "AREEL2", "author_a", NOW.minusSeconds(7200), "a2", "REELS", 1, 1, 3000L);
		insertMetricPost(brandId, "BREEL1", "author_b", NOW.minusSeconds(3600), "b1", "REELS", 1, 1, 9000L);
		insertAuthorProfile("author_a", "author_a", "에이", 100L);
		insertAuthorProfile("author_b", "author_b", "비", 9000L);

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.AGGREGATE_POSTS,
				args().put("brandId", brandId).put("groupBy", "author").put("orderBy", "reachMultiple"));

		assertThat(result.failed()).isFalse();
		JsonNode payload = objectMapper.readTree(result.payloadJson());
		assertThat(payload.path("totalGroups").asInt()).isEqualTo(2);
		assertThat(payload.path("groups").get(0).path("key").asString()).isEqualTo("author_a");
		assertThat(payload.path("groups").get(0).path("reachMultiple").asDouble()).isEqualTo(20.0);
		assertThat(payload.path("groups").get(1).path("key").asString()).isEqualTo("author_b");
		assertThat(payload.path("groups").get(1).path("reachMultiple").asDouble()).isEqualTo(1.0);
	}

	/** 팔로워 미상(author_profile 미수집)이면 reachMultiple은 null이고 정렬 시 뒤로 밀린다(계산 불가 - 제외가 아니라 유지). */
	@Test
	void aggregate_posts_groupBy_author는_팔로워_미상이면_reachMultiple이_null이고_뒤로_정렬된다() throws Exception {
		long brandId = insertBrand(monitoringJdbc, "groupauthornullbrand");
		linkRepository.insertLink(userId, brandId, "groupauthornullbrand", BrandAccountType.OWN, 12);
		insertMetricPost(brandId, "KNOWN1", "known_author", NOW.minusSeconds(3600), "k1", "REELS", 1, 1, 1000L);
		insertMetricPost(brandId, "UNKNOWN1", "unknown_author", NOW.minusSeconds(3600), "u1", "REELS", 1, 1, 5000L);
		insertAuthorProfile("known_author", "known_author", "알려짐", 100L);
		// unknown_author는 author_profile 미수집 - findAuthorsByUsernameBatched가 못 찾아 followers null.

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.AGGREGATE_POSTS,
				args().put("brandId", brandId).put("groupBy", "author").put("orderBy", "reachMultiple"));

		assertThat(result.failed()).isFalse();
		JsonNode payload = objectMapper.readTree(result.payloadJson());
		assertThat(payload.path("totalGroups").asInt()).isEqualTo(2);
		assertThat(payload.path("groups").get(0).path("key").asString()).isEqualTo("known_author");
		assertThat(payload.path("groups").get(0).path("reachMultiple").asDouble()).isEqualTo(10.0);
		assertThat(payload.path("groups").get(1).path("key").asString()).isEqualTo("unknown_author");
		assertThat(payload.path("groups").get(1).path("reachMultiple").isNull()).isTrue();
	}

	/** limit은 반환 그룹 수만 자르고, totalGroups는 절단과 무관하게 전체 그룹 수를 그대로 보고한다(조용한 절단 금지). */
	@Test
	void aggregate_posts_limit은_그룹을_자르되_totalGroups는_전체를_보고한다() throws Exception {
		long brandId = insertBrand(monitoringJdbc, "grouplimitbrand");
		linkRepository.insertLink(userId, brandId, "grouplimitbrand", BrandAccountType.OWN, 12);
		insertMetricPost(brandId, "LIM1", "lim_author1", NOW.minusSeconds(3600), "1", "REELS", 1, 1, 1000L);
		insertMetricPost(brandId, "LIM2", "lim_author2", NOW.minusSeconds(3600), "2", "REELS", 1, 1, 1000L);
		insertMetricPost(brandId, "LIM3", "lim_author3", NOW.minusSeconds(3600), "3", "REELS", 1, 1, 1000L);

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.AGGREGATE_POSTS,
				args().put("brandId", brandId).put("groupBy", "author").put("limit", 2));

		assertThat(result.failed()).isFalse();
		JsonNode payload = objectMapper.readTree(result.payloadJson());
		assertThat(payload.path("totalGroups").asInt()).isEqualTo(3);
		assertThat(payload.path("returnedGroups").asInt()).isEqualTo(2);
		assertThat(payload.path("groups")).hasSize(2);
	}

	/** keyword는 search_posts와 같은 캡션 매칭 헬퍼로 모수를 좁힌다 - 매칭 안 된 게시물은 집계에서 아예 빠진다. */
	@Test
	void aggregate_posts_keyword는_캡션_매칭_게시물만_모수로_삼는다() throws Exception {
		long brandId = insertBrand(monitoringJdbc, "aggkeywordbrand");
		linkRepository.insertLink(userId, brandId, "aggkeywordbrand", BrandAccountType.OWN, 12);
		insertSearchablePost(brandId, "KWMATCH1", "author1", NOW.minusSeconds(3600), "신상 세럼 후기");
		insertSearchablePost(brandId, "KWNOMATCH1", "author2", NOW.minusSeconds(3600), "그냥 일상 게시물");

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.AGGREGATE_POSTS,
				args().put("brandId", brandId).put("keyword", "세럼"));

		assertThat(result.failed()).isFalse();
		assertThat(result.payloadJson()).contains("\"postCount\":1").contains("\"keyword\":\"세럼\"");
	}

	/**
	 * groupBy=month는 KST 달력 월로 버킷한다(스펙 §3-1) - 8/31 23:00 KST(=8/31 14:00Z, 8월)와
	 * 9/1 01:00 KST(=8/31 16:00Z, 9월) 게시물이 서로 다른 버킷에 들어가야 한다(롤링 30일이 아니다).
	 */
	@Test
	void aggregate_posts_groupBy_month는_KST_달력_월로_버킷한다() {
		long brandId = insertBrand(monitoringJdbc, "monthbucketbrand");
		linkRepository.insertLink(userId, brandId, "monthbucketbrand", BrandAccountType.OWN, 12);
		insertMetricPost(brandId, "AUGPOST", "author1", Instant.parse("2026-08-31T14:00:00Z"), "8월", "REELS", 1, 1,
				1000L);
		insertMetricPost(brandId, "SEPPOST", "author2", Instant.parse("2026-08-31T16:00:00Z"), "9월", "REELS", 1, 1,
				1000L);

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.AGGREGATE_POSTS,
				args().put("brandId", brandId).put("groupBy", "month"));

		assertThat(result.failed()).isFalse();
		String payload = result.payloadJson();
		assertThat(payload).contains("\"key\":\"2026-08\"").contains("\"key\":\"2026-09\"");
		assertThat(payload).contains("\"totalGroups\":2");
	}

	/** groupBy 생략 시 기존 스칼라 페이로드가 필드명·모양 그대로 나가야 한다(하위호환 고정, 기존 08-28 테스트와 동형). */
	@Test
	void aggregate_posts_groupBy_없으면_기존_스칼라_페이로드_그대로다() {
		long brandId = insertBrand(monitoringJdbc, "scalarcompatbrand");
		linkRepository.insertLink(userId, brandId, "scalarcompatbrand", BrandAccountType.OWN, 12);
		insertMetricPost(brandId, "SCALAR1", "author1", NOW.minusSeconds(3600), "릴스 하나", "REELS", 10, 2, 1000L);
		insertMetricPost(brandId, "SCALAR2", "author2", NOW.minusSeconds(7200), "피드 하나", "FEED", 5, 1, null);

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.AGGREGATE_POSTS,
				args().put("brandId", brandId));

		assertThat(result.failed()).isFalse();
		String payload = result.payloadJson();
		assertThat(payload).contains("\"postCount\":2").contains("\"reelsCount\":1").contains("\"feedCount\":1");
		assertThat(payload).contains("\"totalViews\":1000").contains("\"avgViews\":1000.0")
				.contains("\"viewsSampleCount\":1");
		assertThat(payload).contains("\"totalLikes\":15").contains("\"avgLikes\":7.5")
				.contains("\"likesSampleCount\":2");
		assertThat(payload).contains("\"totalComments\":3").contains("\"commentsSampleCount\":2");
		assertThat(payload).contains("\"topPost\"").contains("\"shortCode\":\"SCALAR1\"");
		assertThat(payload).doesNotContain("\"groups\"").doesNotContain("\"totalGroups\"");
	}

	// ---------- sponsorship 인자·minSample·서버 강제 caveat(2026-09-01 구조적 품질 개선, 스펙 §3·§4) ----------

	/**
	 * sponsorship은 캡션 문자열이 아니라 BrandSponsorshipClassifier 판정 축이다 - #광고 마커가 있으면
	 * is_paid_partnership이 false여도 협찬으로 판정된다(insertMetricPost는 항상 false로 심는다,
	 * 기존 scope_sponsorship_필터_테스트와 같은 시드 방법). sponsored 2건(광고 마커)·organic 3건(마커 없음)
	 * 중 sponsorship="sponsored"면 postCount는 2여야 한다.
	 */
	@Test
	void aggregate_posts_sponsorship_인자는_협찬_게시물만_모수로_삼는다() {
		long brandId = insertBrand(monitoringJdbc, "sponsoraggbrand");
		linkRepository.insertLink(userId, brandId, "sponsoraggbrand", BrandAccountType.OWN, 12);
		insertMetricPost(brandId, "SPONAGG1", "author1", NOW.minusSeconds(3600), "#광고 필수템 추천", "REELS", 1, 1, 1000L);
		insertMetricPost(brandId, "SPONAGG2", "author2", NOW.minusSeconds(7200), "#광고 요즘 애정템", "REELS", 1, 1, 1000L);
		insertMetricPost(brandId, "ORGAGG1", "author3", NOW.minusSeconds(3600), "그냥 일상 게시물", "REELS", 1, 1, 1000L);
		insertMetricPost(brandId, "ORGAGG2", "author4", NOW.minusSeconds(3600), "오늘 하루 기록", "REELS", 1, 1, 1000L);
		insertMetricPost(brandId, "ORGAGG3", "author5", NOW.minusSeconds(3600), "일상 공유합니다", "REELS", 1, 1, 1000L);

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.AGGREGATE_POSTS,
				args().put("brandId", brandId).put("sponsorship", "sponsored"));

		assertThat(result.failed()).isFalse();
		assertThat(result.payloadJson()).contains("\"postCount\":2");
	}

	/** sponsorship 필터는 resolveWindow 공유 로직이라 list_posts·search_posts도 같은 판정을 받아야 한다. */
	@Test
	void list_posts와_search_posts도_sponsorship_인자를_공유한다() {
		long brandId = insertBrand(monitoringJdbc, "sponsorsharedbrand");
		linkRepository.insertLink(userId, brandId, "sponsorsharedbrand", BrandAccountType.OWN, 12);
		insertSearchablePost(brandId, "SPONSEARCH", "author1", NOW.minusSeconds(3600), "#광고 세럼 후기");
		insertSearchablePost(brandId, "ORGSEARCH", "author2", NOW.minusSeconds(7200), "오가닉 세럼 후기");

		AiToolResult listResult = toolbox.execute(userId, BrandAiToolSpecs.LIST_POSTS,
				args().put("brandId", brandId).put("sponsorship", "sponsored"));
		AiToolResult searchResult = toolbox.execute(userId, BrandAiToolSpecs.SEARCH_POSTS,
				args().put("brandId", brandId).put("query", "세럼").put("sponsorship", "sponsored"));

		assertThat(listResult.failed()).isFalse();
		assertThat(listResult.shortCodes()).containsExactly("SPONSEARCH");
		assertThat(searchResult.failed()).isFalse();
		assertThat(searchResult.payloadJson()).contains("\"totalMatches\":1").contains("SPONSEARCH")
				.doesNotContain("ORGSEARCH");
	}

	/**
	 * minSample=2 - author_multi(릴스 2건, 표본 2)는 남고, author_single1·author_single2(각 1건, 표본 1)는
	 * 정렬 전에 제외된다. 제외된 2개 그룹이 filteredOutBySample로 보고돼야 한다(조용한 절단 금지).
	 */
	@Test
	void aggregate_posts_minSample은_표본_미달_그룹을_제외하고_filteredOutBySample로_보고한다() throws Exception {
		long brandId = insertBrand(monitoringJdbc, "minsamplebrand");
		linkRepository.insertLink(userId, brandId, "minsamplebrand", BrandAccountType.OWN, 12);
		insertMetricPost(brandId, "MS1", "author_multi", NOW.minusSeconds(3600), "1", "REELS", 1, 1, 1000L);
		insertMetricPost(brandId, "MS2", "author_multi", NOW.minusSeconds(7200), "2", "REELS", 1, 1, 2000L);
		insertMetricPost(brandId, "MS3", "author_single1", NOW.minusSeconds(3600), "3", "REELS", 1, 1, 5000L);
		insertMetricPost(brandId, "MS4", "author_single2", NOW.minusSeconds(3600), "4", "REELS", 1, 1, 9000L);

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.AGGREGATE_POSTS,
				args().put("brandId", brandId).put("groupBy", "author").put("minSample", 2));

		assertThat(result.failed()).isFalse();
		JsonNode payload = objectMapper.readTree(result.payloadJson());
		assertThat(payload.path("totalGroups").asInt()).isEqualTo(1);
		assertThat(payload.path("filteredOutBySample").asLong()).isEqualTo(2);
		assertThat(payload.path("groups")).hasSize(1);
		assertThat(payload.path("groups").get(0).path("key").asString()).isEqualTo("author_multi");
	}

	/** keyword 사용은 캡션 문자 매칭일 뿐 협찬 판정이 아니라는 고지가 모델 재량이 아니라 서버 강제여야 한다. */
	@Test
	void aggregate_posts_keyword_사용시_caveats에_캡션_매칭_고지가_강제된다() {
		long brandId = insertBrand(monitoringJdbc, "caveatkeywordbrand");
		linkRepository.insertLink(userId, brandId, "caveatkeywordbrand", BrandAccountType.OWN, 12);
		insertSearchablePost(brandId, "CAVKW1", "author1", NOW.minusSeconds(3600), "신상 세럼 후기");

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.AGGREGATE_POSTS,
				args().put("brandId", brandId).put("keyword", "세럼"));

		assertThat(result.failed()).isFalse();
		assertThat(result.payloadJson()).contains("\"caveats\"").contains("sponsorship 인자를 쓰세요");
	}

	/** 릴스 표본이 1개뿐인 그룹이 반환되면(minSample 미지정) 표본 경고 caveat이 서버 강제로 붙어야 한다. */
	@Test
	void aggregate_posts_소표본_그룹_반환시_caveats에_표본_경고가_강제된다() {
		long brandId = insertBrand(monitoringJdbc, "caveatsamplebrand");
		linkRepository.insertLink(userId, brandId, "caveatsamplebrand", BrandAccountType.OWN, 12);
		insertMetricPost(brandId, "CAVS1", "author_x", NOW.minusSeconds(3600), "x", "REELS", 1, 1, 1000L);
		insertMetricPost(brandId, "CAVS2", "author_y", NOW.minusSeconds(3600), "y", "REELS", 1, 1, 2000L);

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.AGGREGATE_POSTS,
				args().put("brandId", brandId).put("groupBy", "author"));

		assertThat(result.failed()).isFalse();
		String payload = result.payloadJson();
		assertThat(payload).contains("\"caveats\"").contains("2개는 릴스 표본이 1개뿐입니다");
	}

	// ---------- get_comments 배치화(2026-08-31 툴·한계 재설계, 스펙 §3-3) ----------

	@Test
	void get_comments는_shortCodes_배열로_여러_게시물을_한_번에_돌려준다() throws Exception {
		insertPostWithComments(myBrandId, "MULTI1", "multi_author1", 3);
		insertPostWithComments(myBrandId, "MULTI2", "multi_author2", 3);

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.GET_COMMENTS,
				args().set("shortCodes", objectMapper.createArrayNode().add("MULTI1").add("MULTI2")));

		assertThat(result.failed()).isFalse();
		JsonNode payload = objectMapper.readTree(result.payloadJson());
		assertThat(payload.path("posts")).hasSize(2);
		assertThat(payload.path("posts").get(0).path("shortCode").asString()).isEqualTo("MULTI1");
		assertThat(payload.path("posts").get(0).path("comments")).hasSize(3);
		assertThat(payload.path("posts").get(1).path("shortCode").asString()).isEqualTo("MULTI2");
		assertThat(payload.path("posts").get(1).path("comments")).hasSize(3);
		assertThat(payload.path("totalReturned").asInt()).isEqualTo(6);
		assertThat(result.rowCount()).isEqualTo(6);
	}

	@Test
	void get_comments_배열은_게시물당_상한과_총_상한을_지킨다() throws Exception {
		ArrayNode shortCodes = objectMapper.createArrayNode();
		for (int i = 1; i <= 5; i++) {
			String shortCode = "BATCH" + i;
			insertPostWithComments(myBrandId, shortCode, "batch_author" + i, 15);
			shortCodes.add(shortCode);
		}

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.GET_COMMENTS,
				args().set("shortCodes", shortCodes));

		assertThat(result.failed()).isFalse();
		JsonNode payload = objectMapper.readTree(result.payloadJson());
		assertThat(payload.path("posts")).hasSize(5);
		for (JsonNode postNode : payload.path("posts")) {
			assertThat(postNode.path("comments")).hasSize(10);
		}
		assertThat(payload.path("totalReturned").asInt()).isEqualTo(50);
		assertThat(result.rowCount()).isEqualTo(50);
	}

	@Test
	void get_comments는_기존_shortCode_단건_호출과_하위호환된다() {
		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.GET_COMMENTS,
				args().put("shortCode", "MINE1"));

		assertThat(result.failed()).isFalse();
		String payload = result.payloadJson();
		assertThat(payload).contains("\"shortCode\":\"MINE1\"").contains("\"limit\":20")
				.contains("\"returned\":1").contains("너무 예뻐요");
		assertThat(payload).doesNotContain("\"posts\"").doesNotContain("\"notFound\"");
		assertThat(result.shortCodes()).containsExactly("MINE1");
	}

	@Test
	void get_comments_배열에_소유하지_않은_게시물이_섞이면_그_게시물만_빠진다() throws Exception {
		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.GET_COMMENTS,
				args().set("shortCodes", objectMapper.createArrayNode().add("MINE1").add("THEIRS1")));

		assertThat(result.failed()).isFalse();
		JsonNode payload = objectMapper.readTree(result.payloadJson());
		assertThat(payload.path("posts")).hasSize(1);
		assertThat(payload.path("posts").get(0).path("shortCode").asString()).isEqualTo("MINE1");
		assertThat(payload.path("notFound")).hasSize(1);
		assertThat(payload.path("notFound").get(0).asString()).isEqualTo("THEIRS1");
	}

	// ---------- 브랜드 컨텍스트 선주입(2026-08-31 툴·한계 재설계, 스펙 §6) ----------

	@Test
	void brandContextLine은_브랜드_메타를_한_줄로_요약한다() {
		String line = toolbox.brandContextLine(userId, myBrandId);

		assertThat(line).contains("brandId=" + myBrandId).contains("mybrand").contains("1000");
	}

	@Test
	void brandContextLine은_링크가_없으면_빈_문자열이다() {
		assertThat(toolbox.brandContextLine(otherUserId, myBrandId)).isEmpty();
	}

	// ---------- author 축 인자(2026-09-01 실측 id75 후속) ----------
	// 실측: "@showmethe._.a의 릴스 게시물 6개 상세 지표"에 모델이 "작성자 필터가 없다"며 shortCode
	// 입력을 요구했다 - 세 툴이 resolveWindow에서 같은 author 판정을 공유해야 재발하지 않는다.

	@Test
	void list_posts는_author_인자로_특정_작성자_게시물만_돌려준다() {
		long brandId = insertBrand(monitoringJdbc, "authorlistbrand");
		linkRepository.insertLink(userId, brandId, "authorlistbrand", BrandAccountType.OWN, 12);
		insertTaggedPost(monitoringJdbc, brandId, "AUTHORX1", "author_x", NOW.minusSeconds(3600));
		insertTaggedPost(monitoringJdbc, brandId, "AUTHORX2", "author_x", NOW.minusSeconds(7200));
		insertTaggedPost(monitoringJdbc, brandId, "AUTHORY1", "author_y", NOW.minusSeconds(3600));

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.LIST_POSTS,
				args().put("brandId", brandId).put("author", "author_x"));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).containsExactlyInAnyOrder("AUTHORX1", "AUTHORX2");
	}

	@Test
	void search_posts는_author_인자로_그_작성자_게시물_안에서만_검색한다() {
		long brandId = insertBrand(monitoringJdbc, "authorsearchbrand");
		linkRepository.insertLink(userId, brandId, "authorsearchbrand", BrandAccountType.OWN, 12);
		insertSearchablePost(brandId, "ASX1", "author_x", NOW.minusSeconds(3600), "신상 세럼 후기");
		insertSearchablePost(brandId, "ASY1", "author_y", NOW.minusSeconds(3600), "신상 세럼 후기");

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.SEARCH_POSTS,
				args().put("brandId", brandId).put("query", "세럼").put("author", "author_x"));

		assertThat(result.failed()).isFalse();
		assertThat(result.payloadJson()).contains("\"totalMatches\":1").contains("ASX1").doesNotContain("ASY1");
	}

	@Test
	void aggregate_posts는_author_인자로_그_작성자_게시물만_모수로_집계한다() {
		long brandId = insertBrand(monitoringJdbc, "authoraggbrand");
		linkRepository.insertLink(userId, brandId, "authoraggbrand", BrandAccountType.OWN, 12);
		insertMetricPost(brandId, "AAX1", "author_x", NOW.minusSeconds(3600), "x1", "REELS", 1, 1, 1000L);
		insertMetricPost(brandId, "AAX2", "author_x", NOW.minusSeconds(7200), "x2", "REELS", 1, 1, 2000L);
		insertMetricPost(brandId, "AAY1", "author_y", NOW.minusSeconds(3600), "y1", "REELS", 1, 1, 9000L);

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.AGGREGATE_POSTS,
				args().put("brandId", brandId).put("author", "author_x"));

		assertThat(result.failed()).isFalse();
		assertThat(result.payloadJson()).contains("\"postCount\":2");
	}

	/** 모델이 "@xxx" 형태를 그대로 넣는 실측 패턴(id75)을 흡수한다 - 선행 "@" 하나만 벗기고 정확 일치한다. */
	@Test
	void author_인자는_선행_골뱅이_접두를_흡수한다() {
		long brandId = insertBrand(monitoringJdbc, "authoratbrand");
		linkRepository.insertLink(userId, brandId, "authoratbrand", BrandAccountType.OWN, 12);
		insertTaggedPost(monitoringJdbc, brandId, "AT1", "author_z", NOW.minusSeconds(3600));

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.LIST_POSTS,
				args().put("brandId", brandId).put("author", "@author_z"));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).containsExactly("AT1");
	}

	/** author 인자는 대소문자를 무시하고 정확 일치한다(부분일치 아님 - scope의 q와 다른 축). */
	@Test
	void author_인자는_대소문자를_무시하고_정확_일치한다() {
		long brandId = insertBrand(monitoringJdbc, "authorcasebrand");
		linkRepository.insertLink(userId, brandId, "authorcasebrand", BrandAccountType.OWN, 12);
		insertTaggedPost(monitoringJdbc, brandId, "CASE1", "MixedCaseAuthor", NOW.minusSeconds(3600));

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.LIST_POSTS,
				args().put("brandId", brandId).put("author", "mixedcaseauthor"));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).containsExactly("CASE1");
	}

	// ---------- list_posts limit 인자(2026-09-01 실측 id75 후속) ----------

	/** limit 생략 시 기존 기본값(30건)과 동일하게 동작한다(하위호환) - 31건을 심어 30건만 반환되는지 확인. */
	@Test
	void list_posts는_limit_생략시_기존_기본값_30건을_유지한다() {
		long brandId = insertBrand(monitoringJdbc, "defaultlimitbrand");
		linkRepository.insertLink(userId, brandId, "defaultlimitbrand", BrandAccountType.OWN, 12);
		for (int i = 1; i <= 31; i++) {
			insertMetricPost(brandId, "DL" + i, "author" + i, NOW.minusSeconds(i * 60L), "본문" + i, "REELS", 1, 1, 1L);
		}

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.LIST_POSTS, args().put("brandId", brandId));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).hasSize(30);
	}

	/** 사용자가 개수를 명시하면(예: 50) limit 인자가 기본값(30)을 넘겨 그대로 반영돼야 한다(실측 id75 - "게시물 6개"). */
	@Test
	void list_posts는_limit_인자를_명시하면_기본값_30을_넘겨_반영한다() {
		long brandId = insertBrand(monitoringJdbc, "customlimitbrand");
		linkRepository.insertLink(userId, brandId, "customlimitbrand", BrandAccountType.OWN, 12);
		for (int i = 1; i <= 50; i++) {
			insertMetricPost(brandId, "CL" + i, "author" + i, NOW.minusSeconds(i * 60L), "본문" + i, "REELS", 1, 1, 1L);
		}

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.LIST_POSTS,
				args().put("brandId", brandId).put("limit", 50));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).hasSize(50);
	}

	/** limit이 상한(100)을 넘게 요청돼도 모델 요청값과 무관하게 100건으로 잘린다(기존 댓글 50건 상한 관용구와 동형). */
	@Test
	void list_posts는_limit_100_초과_요청을_100건으로_클램프한다() {
		long brandId = insertBrand(monitoringJdbc, "overlimitbrand");
		linkRepository.insertLink(userId, brandId, "overlimitbrand", BrandAccountType.OWN, 12);
		for (int i = 1; i <= 105; i++) {
			insertMetricPost(brandId, "OL" + i, "author" + i, NOW.minusSeconds(i * 60L), "본문" + i, "REELS", 1, 1, 1L);
		}

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.LIST_POSTS,
				args().put("brandId", brandId).put("limit", 9999));

		assertThat(result.failed()).isFalse();
		assertThat(result.shortCodes()).hasSize(100);
	}

	// ---------- 작성자 팔로워 수(2026-09-02, groundedness 가드 후속) ----------

	/** list_posts 행에 작성자 팔로워 수가 실리는지 검증한다 - {@link BrandPostAssembler#hydrate}가 이미
	 * 배치 조회하는 author_profile(resolveAuthors)을 그대로 옮겨 싣는 것이라 추가 쿼리가 없다(F6/T3의
	 * applyAuthorScope처럼 조건부로 도는 조회가 아니다). 골드셋 chain-referent-resolution 실측
	 * 실패("거기서 조회수 젤 높은 사람 프로필 좀 보여줘"에 목록 단계 팔로워 수가 없어 모델이 지어낸
	 * 사례) 후속 - 목록 단계부터 값을 실어 후속 질문에서 지어낼 공백을 없앤다. */
	@Test
	void list_posts는_행에_작성자_팔로워_수를_담는다() {
		long brandId = insertBrand(monitoringJdbc, "followerslistbrand");
		linkRepository.insertLink(userId, brandId, "followerslistbrand", BrandAccountType.OWN, 12);
		insertTaggedPost(monitoringJdbc, brandId, "FOLLOWERS1", "followers_author", NOW.minusSeconds(3600));
		insertAuthorProfile("followers_author", "followers_author", "팔로워 작성자", 192_487L);

		AiToolResult result = toolbox.execute(userId, BrandAiToolSpecs.LIST_POSTS, args().put("brandId", brandId));

		assertThat(result.failed()).isFalse();
		assertThat(result.payloadJson()).contains("\"authorFollowers\":192487");
	}
}
