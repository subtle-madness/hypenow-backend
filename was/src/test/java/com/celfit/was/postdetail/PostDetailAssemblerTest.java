package com.celfit.was.postdetail;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.Content;
import com.celfit.contract.analysis.ContentMetricSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PostDetailAssemblerTest {

	// 게시일(2026-06-28T00:00Z)로부터 10일 경과 시점으로 고정
	private final Clock fixedClock =
			Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

	private final PostDetailAssembler assembler =
			new PostDetailAssembler(JsonMapper.builder().build(), fixedClock);

	private Content reels() {
		return new Content("mari01", "marimood", "https://thumb/mari01.jpg", "쿨톤 여름 침착 조합",
				OffsetDateTime.parse("2026-06-28T00:00:00Z"), "reels", new BigDecimal("18.0"),
				"https://www.instagram.com/p/mari01/", 1911943L, 32969L, 488L, 1911943L, null, null);
	}

	private Account account() {
		return new Account("marimood", "마리 MARI", "https://pic/mari.jpg", 16586L, null);
	}

	@Test
	void 행을_모달_블록_구조로_조립한다() {
		List<CommentRow> comments = List.of(
				new CommentRow(1L, "hye***", "이거 어디서 살 수 있어요??", 342L, "purchase"),
				new CommentRow(3L, "seo***", "언니 피부 미쳤다", 289L, null));

		PostDetailResponse response =
				assembler.toResponse(reels(), account(), comments, Optional.empty(), Optional.empty());

		assertThat(response.post().shortCode()).isEqualTo("mari01");
		assertThat(response.post().daysSincePosted()).isEqualTo(10L);
		// (32969+488)/1911943 = 0.0175 (4자리 HALF_UP)
		assertThat(response.post().engagementRate()).isEqualByComparingTo(new BigDecimal("0.0175"));
		assertThat(response.post().hypeScore()).isEqualTo(1911943L);
		assertThat(response.post().metricsCapturedAt()).isNull();
		assertThat(response.account().displayName()).isEqualTo("마리 MARI");
		assertThat(response.comments().collectedCount()).isEqualTo(2);
		assertThat(response.comments().items().getFirst().authorMasked()).isEqualTo("hye***");
		assertThat(response.comments().items().getFirst().likeCount()).isEqualTo(342L);
		assertThat(response.comments().items().getFirst().aiCategory()).isEqualTo("purchase");
		assertThat(response.comments().items().get(1).aiCategory()).isNull();
		assertThat(response.analysis()).isNull();
	}

	@Test
	void 피드는_조회수가_없어_참여율이_null이다() {
		Content feed = new Content("mari02", "marimood", null, "피드 게시물",
				OffsetDateTime.parse("2026-07-01T00:00:00Z"), "feed", null,
				"https://www.instagram.com/p/mari02/", null, 2000L, 100L, 2100L, null, null);

		PostDetailResponse response =
				assembler.toResponse(feed, account(), List.of(), Optional.empty(), Optional.empty());

		assertThat(response.post().engagementRate()).isNull();
		assertThat(response.post().daysSincePosted()).isEqualTo(7L);
		assertThat(response.comments().collectedCount()).isZero();
		assertThat(response.comments().items()).isEmpty();
	}

	@Test
	void 계정이_없으면_account_블록이_null이다() {
		PostDetailResponse response =
				assembler.toResponse(reels(), null, List.of(), Optional.empty(), Optional.empty());

		assertThat(response.account()).isNull();
		assertThat(response.post().shortCode()).isEqualTo("mari01");
	}

	@Test
	void 게시일이_null이면_경과일도_null이다() {
		Content undated = new Content("mari03", "marimood", null, null,
				null, "reels", null, null, 1000L, 10L, 1L, 1000L, null, null);

		PostDetailResponse response =
				assembler.toResponse(undated, account(), List.of(), Optional.empty(), Optional.empty());

		assertThat(response.post().daysSincePosted()).isNull();
		assertThat(response.post().engagementRate()).isEqualByComparingTo(new BigDecimal("0.0110"));
	}

	@Test
	void 조회수가_0이면_참여율이_null이다() {
		Content zeroViews = new Content("mari04", "marimood", null, null,
				OffsetDateTime.parse("2026-07-01T00:00:00Z"), "reels", null, null,
				0L, 10L, 1L, 0L, null, null);

		PostDetailResponse response =
				assembler.toResponse(zeroViews, account(), List.of(), Optional.empty(), Optional.empty());

		assertThat(response.post().engagementRate()).isNull();
	}

	@Test
	void asOf가_있으면_지표를_스냅샷_값으로_재구성한다() {
		ContentMetricSnapshot snapshot = new ContentMetricSnapshot(12L, "mari01",
				OffsetDateTime.parse("2026-07-04T14:59:59Z"), 500000L, 20000L, 400L, 500000L);
		// endDate=2026-07-03 가정 → 기준 시각 = 2026-07-03T15:00:00Z (KST 07-04 00:00)
		AsOfMetrics asOf = new AsOfMetrics(snapshot, OffsetDateTime.parse("2026-07-03T15:00:00Z"));

		PostDetailResponse response =
				assembler.toResponse(reels(), account(), List.of(), Optional.empty(), Optional.of(asOf));

		assertThat(response.post().views()).isEqualTo(500000L);
		assertThat(response.post().likes()).isEqualTo(20000L);
		assertThat(response.post().comments()).isEqualTo(400L);
		assertThat(response.post().hypeScore()).isEqualTo(500000L);
		// (20000+400)/500000 = 0.0408
		assertThat(response.post().engagementRate()).isEqualByComparingTo(new BigDecimal("0.0408"));
		// postedAt 06-28T00:00Z → 기준 07-03T15:00Z = 5일 15시간 → 5
		assertThat(response.post().daysSincePosted()).isEqualTo(5L);
		assertThat(response.post().metricsCapturedAt())
				.isEqualTo(OffsetDateTime.parse("2026-07-04T14:59:59Z"));
		// 메타는 최신 미러 값 그대로
		assertThat(response.post().caption()).isEqualTo("쿨톤 여름 침착 조합");
	}

	@Test
	void asOf_스냅샷의_조회수가_null이면_참여율도_null이다() {
		ContentMetricSnapshot feedSnapshot = new ContentMetricSnapshot(21L, "mari02",
				OffsetDateTime.parse("2026-07-02T00:00:00Z"), null, 1500L, 80L, 1580L);
		AsOfMetrics asOf = new AsOfMetrics(feedSnapshot, OffsetDateTime.parse("2026-07-02T15:00:00Z"));

		PostDetailResponse response =
				assembler.toResponse(reels(), account(), List.of(), Optional.empty(), Optional.of(asOf));

		assertThat(response.post().views()).isNull();
		assertThat(response.post().engagementRate()).isNull();
		assertThat(response.post().hypeScore()).isEqualTo(1580L);
	}

	private ContentAnalysisRow analysisRow() {
		return new ContentAnalysisRow(
				OffsetDateTime.parse("2026-07-12T00:00:00Z"),
				"본인 평균 대비 3.1배 터진 콘텐츠", "실연형에서 터지는 패턴", "구매 전환형 반응",
				608899L, 1, 7, 12, new BigDecimal("0.0380"), 25574L, 3653L,
				2, 340000L, 880L,
				"[{\"name\":\"Thelavicos\",\"evidence\":\"라벨 정면 반복 노출\"}]", "high",
				"[\"단일 브랜드 반복 클로즈업\"]", "캡션 #협찬 표기 있음", "[\"클렌징\"]",
				"[{\"label\":\"후킹 요소\",\"value\":\"문제 제기형 자막\"}]", "클렌징",
				"[\"필링·각질\"]", "sponsored", "high", "진정성 높음");
	}

	@Test
	void 분석_1행을_analysis_블록으로_조립한다() {
		PostDetailResponse response =
				assembler.toResponse(reels(), account(), List.of(), Optional.of(analysisRow()), Optional.empty());

		PostDetailResponse.Analysis analysis = response.analysis();
		assertThat(analysis.aiContentSummary()).isEqualTo("본인 평균 대비 3.1배 터진 콘텐츠");
		assertThat(analysis.baseline().recentReelsAvgViews()).isEqualTo(608899L);
		assertThat(analysis.baseline().rankInRecentReels()).isEqualTo(1);
		assertThat(analysis.baseline().recent12AvgEngagementRate())
				.isEqualByComparingTo(new BigDecimal("0.0380"));
		assertThat(analysis.categoryContext().topPercentile()).isEqualTo(2);
		assertThat(analysis.categoryContext().avgViews()).isEqualTo(340000L);
		assertThat(analysis.content().detectedBrands()).hasSize(1);
		assertThat(analysis.content().detectedBrands().getFirst().name()).isEqualTo("Thelavicos");
		assertThat(analysis.content().detectedBrands().getFirst().evidence()).isEqualTo("라벨 정면 반복 노출");
		assertThat(analysis.content().sponsoredSignalReasons()).containsExactly("단일 브랜드 반복 클로즈업");
		assertThat(analysis.content().attributes().getFirst().label()).isEqualTo("후킹 요소");
		assertThat(analysis.content().subCategories()).containsExactly("필링·각질");
		assertThat(analysis.content().adType()).isEqualTo("sponsored");
		assertThat(analysis.commentAuthenticity().grade()).isEqualTo("high");
	}

	@Test
	void VLM_미실행이면_jsonb_필드가_null_그대로다() {
		ContentAnalysisRow vlmSkipped = new ContentAnalysisRow(
				OffsetDateTime.parse("2026-07-12T00:00:00Z"),
				"요약", null, null,
				608899L, 1, 7, 12, new BigDecimal("0.0380"), 25574L, 3653L,
				2, 340000L, 880L,
				null, null, null, null, null, null, null, null, null, null, null);

		PostDetailResponse response =
				assembler.toResponse(reels(), account(), List.of(), Optional.of(vlmSkipped), Optional.empty());

		PostDetailResponse.Analysis.Content content = response.analysis().content();
		assertThat(content.detectedBrands()).isNull();
		assertThat(content.sponsoredSignalReasons()).isNull();
		assertThat(content.attributes()).isNull();
		assertThat(content.subCategories()).isNull();
		assertThat(content.adType()).isNull();
	}
}
