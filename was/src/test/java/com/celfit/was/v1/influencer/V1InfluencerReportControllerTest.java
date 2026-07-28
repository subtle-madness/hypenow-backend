package com.celfit.was.v1.influencer;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.config.ClockConfig;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.BrandRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.CategoryRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.CopyRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.PeerStatsRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.ProductRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.SeriesRow;
import com.celfit.was.v1.influencer.V1InfluencerReportRepository.SummaryRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// V1ContentControllerTest와 같은 구성 — Clock은 ClockConfig(시스템 시계, 문구 검증은 어셈블러 테스트 몫).
@WebMvcTest(controllers = V1InfluencerReportController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({V1InfluencerReportAssembler.class, ClockConfig.class, V1ExceptionAdvice.class, SecurityConfig.class})
class V1InfluencerReportControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	V1InfluencerReportRepository repository;

	// followers 10000 · 계정 ER 2.0% · avgViews 52000 · avgLikes 1500 · avgComments 80.
	// lastPostedAt=null → activity.isActive는 항상 false(업로드 이력 없음 케이스 전용 픽스처).
	private SummaryRow fullSummary() {
		return new SummaryRow(10000L, 24L, 321L, "views", 52000L, new BigDecimal("0.42"),
				new BigDecimal("2.0"), 1500L, 80L, null, new BigDecimal("2.5"));
	}

	// 올린 순 4건 — 앞 2건(organic, views/likes/comments 1000·100·10) vs 뒤 2건(광고, 2000·200·20)으로
	// Assembler.growthPct(앞절반 vs 뒤절반)가 +100%로 손계산되도록 구성. 뒤 2건은 sponsored=true·2일 간격.
	private List<SeriesRow> fullSeries() {
		return List.of(
				new SeriesRow(OffsetDateTime.parse("2026-06-30T20:30:00Z"), "reels", 1000L, 100L, 10L, false,
						"캡션1", "https://img/thumb1.jpg", null),
				new SeriesRow(OffsetDateTime.parse("2026-07-02T03:00:00Z"), "reels", 1000L, 100L, 10L, false,
						"캡션2", "https://img/thumb2.jpg", null),
				new SeriesRow(OffsetDateTime.parse("2026-07-04T03:00:00Z"), "feed", 2000L, 200L, 20L, true,
						"캡션3 광고", "https://img/thumb3.jpg", "브랜드A"),
				new SeriesRow(OffsetDateTime.parse("2026-07-06T03:00:00Z"), "feed", 2000L, 200L, 20L, true,
						"캡션4 광고", "https://img/thumb4.jpg", "브랜드A"));
	}

	// peerSize 5(>=MIN_PEER_SIZE 3, 게이트 통과) · topPct*는 임의 배정값(그대로 통과되는지 확인용).
	// 유효 팔로워는 07-28부터 피어 무관(시계열 실반응 측정) — 이 픽스처는 topPct 검증에만 쓰인다.
	private PeerStatsRow fullPeer() {
		return new PeerStatsRow(5L, 80, 75, 70, 65, 90, 85, 80, 75,
				new BigDecimal("4.0"), new BigDecimal("3.5"));
	}

	@Test
	void 성공_응답은_스펙_6_5_구조를_가진다() throws Exception {
		given(repository.findSummary("zingdong__")).willReturn(Optional.of(fullSummary()));
		given(repository.findLatestCopy("zingdong__")).willReturn(Optional.of(
				new CopyRow("태그라인", "[\"뷰티\",\"유머\"]", "성과 요약", "콘텐츠 요약", "광고 요약")));
		given(repository.findSeries("zingdong__")).willReturn(fullSeries());
		given(repository.findCategories("zingdong__")).willReturn(List.of(new CategoryRow("메이크업", 5L)));
		given(repository.findBrands("zingdong__")).willReturn(List.of(new BrandRow("브랜드A", 2L)));
		given(repository.findProducts("zingdong__")).willReturn(List.of(new ProductRow("저자극 선크림", 2L)));
		given(repository.findPeerStats("zingdong__")).willReturn(Optional.of(fullPeer()));

		mockMvc.perform(get("/v1/influencers/zingdong__/ai-report").with(user("tester")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.tagline").value("태그라인"))
				.andExpect(jsonPath("$.data.analyzedCount").value(24))
				.andExpect(jsonPath("$.data.totalPosts").value(321))
				// 유효 팔로워(게시물당 평균 실반응): (110+110+220+220)/4 = 165명, 팔로워 대비 1.65% → 반올림 2.
				// views가 전부 팔로워(10000) 미만이라 바이럴 안분 없음, 좋아요:댓글 10:1이라 앵커 컷도 없음.
				.andExpect(jsonPath("$.data.effectiveFollowers").value(165))
				.andExpect(jsonPath("$.data.effectiveFollowersPct").value(2))
				.andExpect(jsonPath("$.data.stats.metric").value("views"))
				.andExpect(jsonPath("$.data.stats.perfSummary").value("성과 요약"))
				// overall.views: value=summary.avgViews 그대로, growthPct=올린순 앞2(1000)→뒤2(2000)=+100%,
				// topPct=피어 topPctViews 80(피어 표본 5명 ≥ 3 게이트 통과).
				.andExpect(jsonPath("$.data.stats.overall.views.value").value(52000))
				.andExpect(jsonPath("$.data.stats.overall.views.growthPct").value(100))
				.andExpect(jsonPath("$.data.stats.overall.views.topPct").value(80))
				.andExpect(jsonPath("$.data.stats.overall.er.value").value(2.0))
				.andExpect(jsonPath("$.data.stats.overall.likes.value").value(1500))
				.andExpect(jsonPath("$.data.stats.overall.comments.value").value(80))
				// ad 행 — 광고 2건(views 2000×2) 재계산값, er=avg(200+20)*100/10000=2.2, topPct는 피어 ad_* 필드.
				.andExpect(jsonPath("$.data.stats.ad.views.value").value(2000))
				.andExpect(jsonPath("$.data.stats.ad.views.topPct").value(90))
				.andExpect(jsonPath("$.data.stats.ad.er.value").value(2.2))
				.andExpect(jsonPath("$.data.stats.ad.er.topPct").value(85))
				.andExpect(jsonPath("$.data.chart.metric").value("views"))
				.andExpect(jsonPath("$.data.chart.bars").isArray())
				.andExpect(jsonPath("$.data.chart.bars.length()").value(4))
				.andExpect(jsonPath("$.data.chart.bars[0].postedAt").value("2026-07-01"))
				.andExpect(jsonPath("$.data.chart.bars[0].caption").value("캡션1"))
				.andExpect(jsonPath("$.data.chart.bars[0].thumbnailUrl").value("https://img/thumb1.jpg"))
				.andExpect(jsonPath("$.data.chart.bars[2].sponsored").value(true))
				.andExpect(jsonPath("$.data.chart.bars[2].brand").value("브랜드A"))
				.andExpect(jsonPath("$.data.contentMix.contentSummary").value("콘텐츠 요약"))
				.andExpect(jsonPath("$.data.contentMix.categories[0].label").value("메이크업"))
				.andExpect(jsonPath("$.data.contentMix.traits[0]").value("뷰티"))
				.andExpect(jsonPath("$.data.ads.adSummary").value("광고 요약"))
				.andExpect(jsonPath("$.data.ads.strip").isArray())
				.andExpect(jsonPath("$.data.ads.strip[0]").value(false))
				.andExpect(jsonPath("$.data.ads.strip[2]").value(true))
				.andExpect(jsonPath("$.data.ads.adIntervalDays").value(2.0))
				// headline은 lastAdDaysAgo(now 의존) 문구를 포함 — 경과일·문구 자체는 단언하지 않고
				// 광고 이력이 있으니 비어있지 않다는 사실만 확인(문구 검증은 어셈블러 테스트 몫).
				.andExpect(jsonPath("$.data.ads.headline").isNotEmpty())
				.andExpect(jsonPath("$.data.ads.brands[0].name").value("브랜드A"))
				.andExpect(jsonPath("$.data.ads.products[0].name").value("저자극 선크림"))
				.andExpect(jsonPath("$.data.activity.isActive").value(false)) // lastPostedAt 없음
				// 스펙 6.5 v2에서 제거된 구 필드 부재 확인.
				.andExpect(jsonPath("$.data.summary").doesNotExist())
				.andExpect(jsonPath("$.data.trend").doesNotExist());
	}

	@Test
	void 유사_인플루언서_목록() throws Exception {
		given(repository.findSimilar("haeun.log")).willReturn(List.of(
				new V1InfluencerReportRepository.SimilarRow("minji.beauty", "민지", "/img/p.jpg",
						"저자극 스킨케어 성분 리뷰", 3L, 5L)));
		mockMvc.perform(get("/v1/influencers/haeun.log/similar").with(user("tester")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].influencerId").value("minji.beauty"))
				.andExpect(jsonPath("$.data[0].matchPct").value(60)); // 3/5 Jaccard
	}

	@Test
	void 유사_인플루언서_교집합_없으면_matchPct_null() throws Exception {
		given(repository.findSimilar("haeun.log")).willReturn(List.of(
				new V1InfluencerReportRepository.SimilarRow("somi.skin", "소미", null,
						"색조 위주", 0L, 7L)));
		mockMvc.perform(get("/v1/influencers/haeun.log/similar").with(user("tester")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].influencerId").value("somi.skin"))
				.andExpect(jsonPath("$.data[0].matchPct").doesNotExist());
	}

	@Test
	void 유사_인플루언서_없으면_200에_빈_배열() throws Exception {
		given(repository.findSimilar("ghost")).willReturn(List.of());

		mockMvc.perform(get("/v1/influencers/ghost/similar").with(user("tester")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").isArray())
				.andExpect(jsonPath("$.data").isEmpty());
	}

	@Test
	void 없는_인플루언서는_NOT_FOUND() throws Exception {
		given(repository.findSummary("ghost")).willReturn(Optional.empty());

		mockMvc.perform(get("/v1/influencers/ghost/ai-report").with(user("tester")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void 카피_미생성이어도_200에_블록_구조는_유지된다() throws Exception {
		given(repository.findSummary("h")).willReturn(Optional.of(fullSummary()));
		given(repository.findLatestCopy("h")).willReturn(Optional.empty());
		given(repository.findSeries("h")).willReturn(List.of());
		given(repository.findCategories("h")).willReturn(List.of());
		given(repository.findBrands("h")).willReturn(List.of());
		given(repository.findProducts("h")).willReturn(List.of());
		given(repository.findPeerStats("h")).willReturn(Optional.empty());

		mockMvc.perform(get("/v1/influencers/h/ai-report").with(user("tester")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.tagline").doesNotExist())
				// 카피(perfSummary/contentSummary/adSummary)만 null — stats.overall 등 블록 구조는 유지.
				.andExpect(jsonPath("$.data.stats.perfSummary").doesNotExist())
				.andExpect(jsonPath("$.data.stats.overall.views.value").value(52000))
				.andExpect(jsonPath("$.data.stats.overall.views.growthPct").doesNotExist()) // series 없음(n<2)
				.andExpect(jsonPath("$.data.stats.ad").doesNotExist()) // 광고 게시물 없음
				.andExpect(jsonPath("$.data.contentMix.contentSummary").doesNotExist())
				.andExpect(jsonPath("$.data.contentMix.traits").isArray())
				.andExpect(jsonPath("$.data.contentMix.traits").isEmpty())
				.andExpect(jsonPath("$.data.chart.bars").isEmpty())
				.andExpect(jsonPath("$.data.ads.adSummary").doesNotExist())
				.andExpect(jsonPath("$.data.ads.headline").doesNotExist()) // 광고 이력 없음
				.andExpect(jsonPath("$.data.effectiveFollowers").doesNotExist()) // 시계열 없음(측정 불가)
				.andExpect(jsonPath("$.data.activity.isActive").value(false));
	}
}
