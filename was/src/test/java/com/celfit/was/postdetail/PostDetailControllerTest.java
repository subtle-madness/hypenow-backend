package com.celfit.was.postdetail;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.Content;
import com.celfit.contract.analysis.ContentComment;
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
				new ContentComment(1L, "mari01", "hye***", "이거 어디서 살 수 있어요??", 342L),
				new ContentComment(3L, "mari01", "seo***", "언니 피부 미쳤다", 289L)));
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
}
