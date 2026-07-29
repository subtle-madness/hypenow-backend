package com.celfit.was.v1.content;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.config.SecurityConfig;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import com.celfit.was.v1.content.V1ContentReportRepository.CommentRow;
import com.celfit.was.v1.content.V1ContentReportRepository.ReelPointRow;
import com.celfit.was.v1.content.V1ContentReportRepository.ReportRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

// 서비스가 리포지토리·조립을 캡슐화하므로 컨트롤러 테스트는 서비스만 목킹한다.
// 정상 케이스는 어셈블러를 테스트 안에서 직접 인스턴스화해 같은 스텁 입력으로 기대 리포트를 조립한다.
@WebMvcTest(controllers = V1ContentReportController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({V1ExceptionAdvice.class, SecurityConfig.class})
class V1ContentReportControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	V1ContentReportService service;

	private final V1ContentReportAssembler assembler = new V1ContentReportAssembler(new ObjectMapper());

	private ReportRow fullRow() {
		return new ReportRow("SC1", "zingdong__", "reels", 3307180L, 42216L, 86L, 100000L,
				"요약", "패턴 서술", "댓글 인사이트",
				412L, 1, 12,
				24, new BigDecimal("1.29"), 35000L, 120L,
				5, 250000L, 480L,
				"makeup", "[{\"name\":\"머지\",\"evidence\":\"로고 노출\"}]", "high",
				"[\"협찬 문구\"]", "명시", "[\"아이라이너\"]",
				"[{\"label\":\"톤\",\"value\":\"쿨톤\"}]",
				"good", "실구매 후기 다수", "timely", "메이크업");
	}

	@Test
	void 성공_응답은_스펙_6_3_구조를_가진다() throws Exception {
		ReportRow row = fullRow();
		List<ReelPointRow> reels = List.of(
				new ReelPointRow("SC1", 1000L, OffsetDateTime.parse("2026-06-30T20:30:00Z"))); // KST 07-01, 본인 릴스
		V1ContentReportRepository.CategoryContextRow categoryContext =
				new V1ContentReportRepository.CategoryContextRow(200L, 41713L, 19L);
		Map<String, Long> categoryCounts = Map.of("purchase", 3L, "adAware", 1L);
		List<CommentRow> comments = List.of(new CommentRow(7L, "u***", "좋아요", 5L, "purchase"));
		given(service.report("SC1")).willReturn(
				assembler.toReport(row, reels, categoryContext, categoryCounts, comments));

		mockMvc.perform(get("/v1/contents/SC1/ai-report").with(user("tester")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.scope.basis").value("recent-posts"))
				.andExpect(jsonPath("$.data.scope.analyzedCount").value(24))
				.andExpect(jsonPath("$.data.summary").value("요약"))
				// 라이브 재계산: baseline=avg(1000)=1000, multiple=3307180/1000 (프리즈 baseline 412 무시)
				.andExpect(jsonPath("$.data.comparison.views.multiple").value(3307.2))
				.andExpect(jsonPath("$.data.comparison.views.rankInRecent").value(1))
				.andExpect(jsonPath("$.data.comparison.views.recentReels[0].postedAt").value("2026-07-01"))
				.andExpect(jsonPath("$.data.comparison.views.recentReels[0].contentId").value("SC1"))
				.andExpect(jsonPath("$.data.comparison.views.recentReels[0].isCurrent").value(true))
				// 참여율은 팔로워(100000) 기준: value=(42216+86)/100000×100=42.30, baseline=(35000+120)/100000×100=35.12
				.andExpect(jsonPath("$.data.comparison.engagementRate.value").value(42.30))
				.andExpect(jsonPath("$.data.comparison.engagementRate.baseline").value(35.12))
				.andExpect(jsonPath("$.data.comparison.engagementQuality.likes.baselineCount").value(35000))
				.andExpect(jsonPath("$.data.comparison.narrative").value("패턴 서술"))
				.andExpect(jsonPath("$.data.categoryContext.categoryLabel").value("메이크업"))
				// 라이브 집계: 표본 200 중 19건이 더 높음 → 20위 → 상위 10% (프리즈 컬럼 5 무시)
				.andExpect(jsonPath("$.data.categoryContext.percentile").value(10))
				.andExpect(jsonPath("$.data.categoryContext.categoryAvgViews").value(41713))
				.andExpect(jsonPath("$.data.categoryContext.sampleSize").value(200))
				.andExpect(jsonPath("$.data.vlmAnalysis.brands[0].name").value("머지"))
				.andExpect(jsonPath("$.data.vlmAnalysis.sponsoredSignal.level").value("high"))
				.andExpect(jsonPath("$.data.vlmAnalysis.attributes[0].label").value("톤"))
				.andExpect(jsonPath("$.data.commentAnalysis.signals.adAversionRate").value(0.25))
				.andExpect(jsonPath("$.data.commentAnalysis.insight").value("댓글 인사이트"))
				.andExpect(jsonPath("$.data.comments[0].id").value("7"))
				.andExpect(jsonPath("$.data.comments[0].category").value("purchase"));
	}

	@Test
	void 없는_콘텐츠나_분석_미생성은_NOT_FOUND() throws Exception {
		given(service.report("NOPE")).willThrow(V1ApiException.notFound("콘텐츠를 찾을 수 없습니다."));

		mockMvc.perform(get("/v1/contents/NOPE/ai-report").with(user("tester")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void vlm_미실행이어도_구조는_유지된다() throws Exception {
		// 팔로워 null(account_summaries 미매칭) → 참여율 value null 유지 (가드)
		ReportRow row = new ReportRow("SC2", "handle", "feed", null, 100L, 10L, null,
				"요약", null, null,
				null, null, null,
				null, null, null, null,
				null, null, null,
				null, null, null, null, null, null, null,
				null, null, null, null);
		given(service.report("SC2")).willReturn(
				assembler.toReport(row, List.of(), null, Map.of(), List.of()));

		mockMvc.perform(get("/v1/contents/SC2/ai-report").with(user("tester")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.vlmAnalysis.brands").isArray())
				.andExpect(jsonPath("$.data.vlmAnalysis.brands").isEmpty())
				.andExpect(jsonPath("$.data.vlmAnalysis.sponsoredSignal.reasons").isEmpty())
				.andExpect(jsonPath("$.data.vlmAnalysis.sponsoredSignal.level").doesNotExist())
				.andExpect(jsonPath("$.data.comparison.views.value").doesNotExist())
				.andExpect(jsonPath("$.data.comparison.engagementRate.value").doesNotExist())
				.andExpect(jsonPath("$.data.commentAnalysis.signals.adAversionRate").value(0.00))
				.andExpect(jsonPath("$.data.comments").isEmpty());
	}
}
