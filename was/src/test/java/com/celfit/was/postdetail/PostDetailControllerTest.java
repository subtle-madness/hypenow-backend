package com.celfit.was.postdetail;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.Content;
import com.celfit.was.config.ClockConfig;
import com.celfit.was.config.WebConfig;
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

@WebMvcTest(controllers = PostDetailController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000,https://celfit-front.vercel.app")
@Import({PostDetailAssembler.class, ClockConfig.class, WebConfig.class})
class PostDetailControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	PostDetailRepository repository;

	private void givenMari01() {
		given(repository.findContent("mari01")).willReturn(Optional.of(
				new Content("mari01", "marimood", "https://thumb/mari01.jpg", "쿨톤 여름 침착 조합",
						OffsetDateTime.parse("2026-06-28T00:00:00Z"), "reels", new BigDecimal("18.0"),
						"https://www.instagram.com/p/mari01/", 1911943L, 32969L, 488L, 1911943L)));
		given(repository.findAccount("marimood")).willReturn(Optional.of(
				new Account("marimood", "마리 MARI", "https://pic/mari.jpg", 16586L)));
		given(repository.findComments("mari01")).willReturn(List.of(
				new CommentRow(1L, "hye***", "이거 어디서 살 수 있어요??", 342L, "purchase"),
				new CommentRow(3L, "seo***", "언니 피부 미쳤다", 289L, null)));
		given(repository.findAnalysis("mari01")).willReturn(Optional.of(new ContentAnalysisRow(
				OffsetDateTime.parse("2026-07-12T00:00:00Z"),
				"본인 평균 대비 3.1배 터진 콘텐츠", "실연형에서 터지는 패턴", "구매 전환형 반응",
				608899L, 1, 7, 12, new BigDecimal("0.0380"), 25574L, 3653L,
				2, 340000L, 880L,
				"[{\"name\":\"Thelavicos\",\"evidence\":\"라벨 정면 반복 노출\"}]", "high",
				"[\"단일 브랜드 반복 클로즈업\"]", "캡션 #협찬 표기 있음", "[\"클렌징\"]",
				"[{\"label\":\"후킹 요소\",\"value\":\"문제 제기형 자막\"}]", "클렌징",
				"[\"필링·각질\"]", "sponsored", "high", "진정성 높음")));
	}

	@Test
	void 게시물_상세를_블록_JSON으로_반환한다() throws Exception {
		givenMari01();

		mockMvc.perform(get("/api/posts/mari01"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.post.shortCode").value("mari01"))
				.andExpect(jsonPath("$.post.engagementRate").value(0.0175))
				.andExpect(jsonPath("$.post.views").value(1911943))
				.andExpect(jsonPath("$.account.handle").value("marimood"))
				.andExpect(jsonPath("$.account.followers").value(16586))
				.andExpect(jsonPath("$.comments.collectedCount").value(2))
				.andExpect(jsonPath("$.comments.items[0].authorMasked").value("hye***"))
				.andExpect(jsonPath("$.comments.items[0].likeCount").value(342));
	}

	@Test
	void 없는_게시물이면_404() throws Exception {
		given(repository.findContent("nope")).willReturn(Optional.empty());

		mockMvc.perform(get("/api/posts/nope"))
				.andExpect(status().isNotFound());
	}

	@Test
	void 허용_오리진에_CORS_헤더를_내린다() throws Exception {
		givenMari01();

		mockMvc.perform(get("/api/posts/mari01")
						.header("Origin", "https://celfit-front.vercel.app"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin",
						"https://celfit-front.vercel.app"));
	}

	@Test
	void 비허용_오리진은_CORS로_차단된다() throws Exception {
		givenMari01();

		mockMvc.perform(get("/api/posts/mari01")
						.header("Origin", "https://evil.example.com"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}
}
