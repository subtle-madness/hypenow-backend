package com.celfit.was.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.ContentCacheSeed;
import com.celfit.was.IntegrationTest;
import com.celfit.was.config.CacheConfig;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.content.ContentAiReport;
import com.celfit.was.v1.content.ContentAiReport.CategoryContext;
import com.celfit.was.v1.content.ContentAiReport.Comment;
import com.celfit.was.v1.content.ContentAiReport.CommentAnalysis;
import com.celfit.was.v1.content.ContentAiReport.CommentAnalysis.Signals;
import com.celfit.was.v1.content.ContentAiReport.CommentAnalysis.Signals.Authenticity;
import com.celfit.was.v1.content.ContentAiReport.CommentAnalysis.Slice;
import com.celfit.was.v1.content.ContentAiReport.Comparison;
import com.celfit.was.v1.content.ContentAiReport.Comparison.EngagementQuality;
import com.celfit.was.v1.content.ContentAiReport.Comparison.EngagementQuality.Counts;
import com.celfit.was.v1.content.ContentAiReport.Comparison.EngagementRate;
import com.celfit.was.v1.content.ContentAiReport.Comparison.Views;
import com.celfit.was.v1.content.ContentAiReport.Comparison.Views.ReelPoint;
import com.celfit.was.v1.content.ContentAiReport.Scope;
import com.celfit.was.v1.content.ContentAiReport.VlmAnalysis;
import com.celfit.was.v1.content.ContentAiReport.VlmAnalysis.Attribute;
import com.celfit.was.v1.content.ContentAiReport.VlmAnalysis.Brand;
import com.celfit.was.v1.content.ContentAiReport.VlmAnalysis.SponsoredSignal;
import com.celfit.was.v1.content.ContentCardRow;
import com.celfit.was.v1.content.V1ContentController;
import com.celfit.was.v1.content.V1ContentPageService;
import com.celfit.was.v1.content.V1ContentPageService.ContentPage;
import com.celfit.was.v1.content.V1ContentQuery;
import com.celfit.was.v1.influencer.InfluencerAiReport;
import com.celfit.was.v1.influencer.InfluencerAiReport.Activity;
import com.celfit.was.v1.influencer.InfluencerAiReport.Ads;
import com.celfit.was.v1.influencer.InfluencerAiReport.Chart;
import com.celfit.was.v1.influencer.InfluencerAiReport.ContentMix;
import com.celfit.was.v1.influencer.InfluencerAiReport.Stats;
import com.celfit.was.v1.influencer.InfluencerAiReport.Trend;
import com.celfit.was.v1.influencer.InfluencerCard;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryAssembler;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryPageService.DiscoveryPage;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.BrandRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.CardRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.ShareRow;
import com.celfit.was.v1.influencer.V1InfluencerDiscoveryRepository.ThumbRow;
import com.celfit.was.v1.influencer.V1InfluencerReportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/**
 * 캐시 왕복 대표 검증은 6.1 경로(히트·TTL·프리페치) — 나머지 캐시 3종은 같은 CacheConfig
 * 메커니즘이라 직렬화 왕복·TTL 등급만 별도 검증한다(스펙 §8). prefix에는 cacheEpoch(빌드 시각,
 * CacheConfig 참조)가 섞여 들어가므로 TTL 단언은 redis-cli --scan으로 실제 키를 찾아서 검증한다
 * (하드코딩 금지 — epoch는 테스트 실행마다 build-info.properties 값에 좌우된다).
 */
class CacheIntegrationTest extends IntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
			.withExposedPorts(6379);

	static {
		REDIS.start();
	}

	@DynamicPropertySource
	static void redisProps(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	CacheManager cacheManager;

	@Autowired
	V1ContentPageService pageService;

	@Autowired
	V1ContentController controller;

	@Autowired
	V1InfluencerReportService influencerReportService;

	@BeforeEach
	void setUp() throws Exception {
		ContentCacheSeed.reset(jdbcTemplate);
		// cacheManager.getCache(name).clear()는 (immediateWrites 적용 전) 비동기 evict라 다음 테스트로
		// 값이 새는 사례가 실측됐다 — flushall은 동기 명령이라 확실하고, resolve된 캐시 이름 목록에
		// 의존하지 않아 런타임에 새로 생성되는 캐시도 같이 비운다.
		REDIS.execInContainer("redis-cli", "flushall");
	}

	private static V1ContentQuery q(int limit, int offset) {
		LocalDate today = LocalDate.now(KST);
		return V1ContentQuery.of(today.minusDays(7), today,
				null, null, null, null, null, null, null, null, null, limit, offset);
	}

	@Test
	void 두번째_호출은_캐시_히트라_DB_변경이_안_보이고_미스_응답과_히트_응답이_완전히_같다() {
		V1ContentQuery query = q(1, 0);
		var first = pageService.page(query);
		assertThat(first.rows()).extracting(ContentCardRow::shortCode).containsExactly("c1");
		assertThat(first.total()).isEqualTo(2);
		// videoDuration(BigDecimal)까지 채운 행이라 JSON 왕복에서 scale이 틀어지면 이 단언에서 걸린다.
		assertThat(first.rows().get(0).videoDuration()).isEqualByComparingTo("15.5");

		jdbcTemplate.update("UPDATE contents SET caption = '변경됨' WHERE short_code = 'c1'");

		var second = pageService.page(query);
		assertThat(second.rows().get(0).caption()).isEqualTo("수분크림 리뷰"); // 캐시 히트 증거(TTL 내 stale 허용)
		// 미스 응답(first)과 히트 응답(second)이 record 단위로 완전 동일 — JSON 왕복(BigDecimal scale·
		// 중첩 record 포함)이 원본과 다른 값을 만들지 않는다는 것을 한 단언으로 고정(리뷰 체크리스트 A).
		// postedAt·metricCapturedAt은 Postgres JDBC 드라이버가 애초에 UTC offset으로 돌려주므로
		// (OffsetDateTime 오프셋 정규화 테스트가 잡는 +09:00 케이스와 무관) 이 단언은 offset 정규화가
		// 아니라 순수 JSON 왕복 무손실만 검증한다.
		assertThat(second).isEqualTo(first);
	}

	@Test
	void 캐시_키에_TTL이_1시간_등급으로_설정된다() throws Exception {
		V1ContentQuery query = q(1, 0);
		pageService.page(query);
		String key = findRedisKey(query.cacheKey());
		var result = REDIS.execInContainer("redis-cli", "ttl", key);
		long ttl = Long.parseLong(result.getStdout().trim());
		assertThat(ttl).isBetween(1L, 3600L); // content-ranking 1h (스펙 §4)
	}

	@Test
	void 리포트_캐시는_6시간_등급_TTL이다() throws Exception {
		InfluencerAiReport report = influencerAiReportNoCopy();
		cacheManager.getCache(CacheConfig.INFLUENCER_REPORT).put("ttl-test-influencer", report);
		String key = findRedisKey("influencer-report:ttl-test-influencer");
		var result = REDIS.execInContainer("redis-cli", "ttl", key);
		long ttl = Long.parseLong(result.getStdout().trim());
		// content-report·influencer-report 등급(6h)은 목록 등급(1h)보다 항상 커야 한다(스펙 §4 대칭 검증).
		assertThat(ttl).isGreaterThan(3600L).isLessThanOrEqualTo(21600L);
	}

	@Test
	void 응답_후_다음_페이지가_프리페치된다() throws Exception {
		// 컨트롤러 호출과 next() 캐시 키 조립이 같은 "오늘"을 봐야 한다 — 각각 LocalDate.now()를
		// 따로 부르면 자정 경계에서 날짜가 어긋나 두 캐시 키가 서로 달라질 수 있다.
		LocalDate today = LocalDate.now(KST);
		controller.contents(null, today.minusDays(7), today,
				null, null, null, null, null, null, null, null, null, 1, 0);
		V1ContentQuery next = V1ContentQuery.of(today.minusDays(7), today,
				null, null, null, null, null, null, null, null, null, 1, 0).next();
		Cache cache = cacheManager.getCache(CacheConfig.CONTENT_RANKING);
		awaitCached(cache, next.cacheKey());
		var cached = (ContentPage) cache.get(next.cacheKey()).get();
		assertThat(cached.rows()).extracting(ContentCardRow::shortCode).containsExactly("c2");
	}

	@Test
	void OffsetDateTime은_왕복시_인스턴트는_보존되고_오프셋은_UTC로_정규화된다() {
		OffsetDateTime kstTime = OffsetDateTime.now(ZoneOffset.ofHours(9)).withNano(0);
		ContentCardRow row = new ContentCardRow("c1", null, "caption", kstTime, "reels", null, null,
				100L, 10L, 1L, 50L, kstTime, null, null, null, null, null, null, "glow", "글로우",
				null, 20000L, new BigDecimal("50"));
		ContentPage page = new ContentPage(List.of(row), 1L);

		Cache cache = cacheManager.getCache(CacheConfig.CONTENT_RANKING);
		cache.put("offset-normalize-test", page);
		ContentPage back = (ContentPage) cache.get("offset-normalize-test").get();

		OffsetDateTime roundTripped = back.rows().get(0).postedAt();
		// CacheConfig 주석의 계약: 같은 순간이지만(instant 비교 true) 문자 그대로는 다르다(offset이
		// +09:00 → UTC로 바뀌므로 strict equals는 false) — 캐시 DTO는 instant 기반으로만 소비해야 함.
		// AssertJ의 OffsetDateTime 전용 isEqualTo/isNotEqualTo는 내부적으로 instant 비교(timeLineOrder)를
		// 쓰므로 strict equals 검증에는 쓸 수 없다 — boolean 등호로 직접 확인한다.
		assertThat(roundTripped.isEqual(kstTime)).isTrue();
		assertThat(roundTripped.equals(kstTime)).isFalse();
		assertThat(roundTripped.getOffset()).isEqualTo(ZoneOffset.UTC);
		assertThat(kstTime.getOffset()).isNotEqualTo(ZoneOffset.UTC); // 원본이 애초에 UTC가 아니었다는 전제 확인
		// record 단위로도 같은 계약이 성립 — ContentPage.equals는 필드별 OffsetDateTime.equals를 타므로
		// 오프셋이 정규화된 back은 원본 page와 record 레벨에서 더 이상 같지 않다.
		assertThat(back).isNotEqualTo(page);
	}

	@Test
	void 발굴_카드_중첩_레코드가_JSON_왕복된다() {
		InfluencerCard card = new V1InfluencerDiscoveryAssembler().toCards(
				// CardRow(handle, displayName, profileImageUrl, followers, postsCount, followsCount,
				// biography, tagline, viewsPerFollower, avgErPct, avgViews, avgLikes, avgComments,
				// avgHypeScore, sponsoredCount, email, avgHypeScorePrecise) — 계획 스니펫이 필드 수와
				// 어긋나 실 record 기준으로 바로잡음(2026-07-29). avgHypeScorePrecise는 2026-07-30 신설(스펙 §10).
				List.of(new CardRow("glow", "글로우", null, 20000L, 214L, 380L, "소개",
						"저자극 톤", new BigDecimal("12.4"), new BigDecimal("3.8"),
						413200L, 10370L, 152L, 72L, 3L, "glow@example.com", new BigDecimal("72.5000"), 1L)),
				List.of(new ShareRow("glow", "skincare", 80), new ShareRow("glow", "makeup", 20)),
				List.of(new BrandRow("glow", "롬앤")),
				List.of(new ThumbRow("glow", "c1", null, "reels", "skincare", "organic",
						OffsetDateTime.now(ZoneOffset.UTC), 1000L, 100L, 10L)),
				List.of())
				.get(0);
		// 협업 브랜드는 어셈블러가 이미 불변 List(toList())로 넘기지만, categoryShares 재료 자체를
		// List.of()로 명시해 불변 입력이 캐시 왕복 후에도 record equals(List 인터페이스 계약, 구현체
		// 무관)로 그대로 살아남는지 확인한다(리뷰 체크리스트 C).
		assertThat(card.categoryShares()).isNotEmpty();
		assertThat(card.recentThumbs()).isNotEmpty();
		assertThat(card.reachMultiplier().scale()).isEqualTo(1);
		assertThat(card.email()).isEqualTo("glow@example.com"); // biography 정규식 파싱(V46) 캐시 왕복 확인

		DiscoveryPage page = new DiscoveryPage(List.of(card), 1L);

		Cache cache = cacheManager.getCache(CacheConfig.INFLUENCER_DISCOVERY);
		cache.put("roundtrip", page);
		DiscoveryPage back = (DiscoveryPage) cache.get("roundtrip").get();

		assertThat(back).isEqualTo(page);
	}

	@Test
	void 콘텐츠_AI_리포트_전체_필드가_JSON_왕복된다() {
		ContentAiReport report = new ContentAiReport(
				new Scope("recent12", 12),
				"요약 문구",
				new Comparison(
						new Views(1000L, 500L, new BigDecimal("2.0"), 1, 12,
								List.of(new ReelPoint("c1", 1000L, "2026-07-20", true),
										new ReelPoint("c0", null, "2026-07-18", false))), // views=null 케이스
						new EngagementRate(new BigDecimal("4.5"), new BigDecimal("3.0")),
						new EngagementQuality(new Counts(100L, 80L), new Counts(10L, 8L)),
						"평균보다 2배 높습니다"),
				new CategoryContext("스킨케어", 90, 500L, 120L),
				new VlmAnalysis(
						List.of(new Brand("롬앤", "입술 클로즈업")),
						new SponsoredSignal("high", List.of("협찬 태그", "할인 코드")),
						"유료광고 포함",
						List.of("스킨케어", "선케어"),
						List.of(new Attribute("텍스처", "촉촉함"))),
				new CommentAnalysis(
						List.of(new Slice("긍정", new BigDecimal("60.0")), new Slice("부정", new BigDecimal("10.0"))),
						new Signals(new BigDecimal("5.0"), new BigDecimal("15.0"),
								new Authenticity("A", "진성 반응 다수")),
						"댓글 반응 긍정적"),
				List.of(new Comment("1", "us**", "좋아요", 5L, "긍정"),
						new Comment("2", "kk**", "가격 문의", 0L, "질문")));

		Cache cache = cacheManager.getCache(CacheConfig.CONTENT_REPORT);
		cache.put("content-report-full", report);
		ContentAiReport back = (ContentAiReport) cache.get("content-report-full").get();

		assertThat(back).isEqualTo(report);
	}

	@Test
	void 콘텐츠_AI_리포트_카테고리_맥락_null도_JSON_왕복된다() {
		ContentAiReport report = new ContentAiReport(
				new Scope("recent12", 3),
				"요약 문구",
				new Comparison(
						new Views(null, null, null, null, null, List.of()),
						new EngagementRate(null, null),
						new EngagementQuality(new Counts(null, null), new Counts(null, null)),
						null),
				null, // 대분류 미분류 — categoryContext 자체가 정의되지 않는 케이스(V1ContentReportService 참조)
				new VlmAnalysis(List.of(), null, null, List.of(), List.of()),
				new CommentAnalysis(List.of(), new Signals(null, null, null), null),
				List.of());

		Cache cache = cacheManager.getCache(CacheConfig.CONTENT_REPORT);
		cache.put("content-report-no-category", report);
		ContentAiReport back = (ContentAiReport) cache.get("content-report-no-category").get();

		assertThat(back).isEqualTo(report);
		assertThat(back.categoryContext()).isNull();
	}

	@Test
	void 인플루언서_AI_리포트_카피_없음_케이스가_JSON_왕복된다() {
		InfluencerAiReport report = influencerAiReportNoCopy();

		Cache cache = cacheManager.getCache(CacheConfig.INFLUENCER_REPORT);
		cache.put("influencer-report-no-copy", report);
		InfluencerAiReport back = (InfluencerAiReport) cache.get("influencer-report-no-copy").get();

		assertThat(back).isEqualTo(report);
	}

	@Test
	void 존재하지_않는_인플루언서는_404이고_캐시에_남지_않는다() {
		assertThatThrownBy(() -> influencerReportService.report("no-such-influencer"))
				.isInstanceOf(V1ApiException.class);

		Cache cache = cacheManager.getCache(CacheConfig.INFLUENCER_REPORT);
		assertThat(cache.get("no-such-influencer")).isNull();
	}

	@Test
	void 인플루언서_리포트는_서비스_경유_두번째_호출도_캐시_히트다() {
		// @Cacheable 배선 자체를 실증한다 — 지금까지의 리포트 라운드트립 테스트는 cacheManager에 직접
		// put/get한 것이라 @Cacheable을 지워도 초록으로 남는다(리뷰 지적). account_summaries 시드
		// 행(handle='glow')을 거쳐 실제 서비스 캐시 히트를 확인한다.
		InfluencerAiReport first = influencerReportService.report("glow");
		assertThat(first.stats().avgViews()).isEqualTo(50000L);

		jdbcTemplate.update("UPDATE account_summaries SET avg_views = 999999 WHERE handle = 'glow'");

		InfluencerAiReport second = influencerReportService.report("glow");
		assertThat(second.stats().avgViews()).isEqualTo(50000L); // 캐시 히트 증거(DB 변경이 안 보임)
		assertThat(second).isEqualTo(first);
	}

	/** account_analyses(계정 LLM 카피) 미생성 케이스 — tagline·summary 등 카피 계열은 null,
	 *  nullable Long/Boolean(avgViews·isActive 등)과 List&lt;Boolean&gt; strip은 값 그대로 유지된다. */
	private static InfluencerAiReport influencerAiReportNoCopy() {
		return new InfluencerAiReport(
				null, // tagline — 카피 없음
				12L, 30L,
				null, // summary — 카피 없음
				new Stats("views_per_follower", null, null, null, null, null), // 릴스 없음 → 산출 불가 null
				new Trend(null, null),
				new Chart("views", null, List.of(
						new Chart.Bar(1000L, 100L, 10L, "2026-07-20", true, "reels"),
						new Chart.Bar(null, null, null, "2026-07-18", false, "feed"))),
				new ContentMix(List.of(new ContentMix.Category("스킨케어", 8L)), List.of()),
				new Ads(0L, List.of(false, false, true), null,
						new Ads.Comparison("views", 0L, null, 0L, null, null),
						null, List.of()),
				new Activity(40L, false, null, null));
	}

	private static void awaitCached(Cache cache, String key) throws InterruptedException {
		for (int i = 0; i < 50; i++) {
			if (cache.get(key) != null) {
				return;
			}
			Thread.sleep(100);
		}
		Assertions.fail("프리페치 미적재: " + key);
	}

	/** cacheEpoch(빌드 시각)가 prefix에 섞여 들어가 정확한 키를 조립할 수 없으므로(CacheConfig
	 *  참조) --scan으로 접미사 매치를 찾는다. 접미사는 해시(hex)거나 캐시명:id라 glob 특수문자가 없다. */
	private static String findRedisKey(String suffix) throws Exception {
		var scan = REDIS.execInContainer("redis-cli", "--scan", "--pattern", "*" + suffix);
		List<String> keys = Stream.of(scan.getStdout().split("\n"))
				.map(String::trim).filter(s -> !s.isEmpty()).toList();
		assertThat(keys).as("redis key matching suffix " + suffix).hasSize(1);
		return keys.get(0);
	}
}
