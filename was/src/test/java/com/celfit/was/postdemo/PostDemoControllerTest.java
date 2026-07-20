package com.celfit.was.postdemo;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PostDemoController.class)
class PostDemoControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PostDemoRepository postDetailRepository;

	private Map<String, Object> post() {
		Map<String, Object> post = new HashMap<>();
		post.put("short_code", "CU2HcR4FYzW");
		post.put("account_handle", "tester");
		post.put("display_name", "테스터");
		post.put("followers", 12000L);
		post.put("caption", "컨실러 협찬 콘텐츠");
		post.put("content_type", "reels");
		post.put("likes", 45493L);
		post.put("main_category", "makeup");
		post.put("ad_type", "sponsored");
		post.put("ai_content_summary", "계정 평균 수준의 성과");
		post.put("detected_brands", "[{\"name\":\"루나\",\"evidence\":\"캡션 언급\"}]");
		// JDBC는 timestamptz를 java.sql.Timestamp로 돌려준다 — 실런타임 타입 그대로 고정
		post.put("posted_at", java.sql.Timestamp.valueOf("2026-07-01 10:00:00"));
		post.put("analyzed_at", java.sql.Timestamp.valueOf("2026-07-14 18:00:00"));
		post.put("model", "claude-opus-4-8");
		return post;
	}

	@Test
	void 게시물_상세는_분석_섹션을_렌더한다() throws Exception {
		given(postDetailRepository.find("CU2HcR4FYzW")).willReturn(Optional.of(post()));
		given(postDetailRepository.commentBreakdown("CU2HcR4FYzW"))
				.willReturn(List.of(Map.of("ai_category", "purchase", "cnt", 5L)));
		given(postDetailRepository.topComments("CU2HcR4FYzW", 5))
				.willReturn(List.of(Map.of("author_masked", "tes***", "body", "어디서 사요?", "like_count", 7L)));

		mockMvc.perform(get("/posts/CU2HcR4FYzW"))
				.andExpect(status().isOk())
				.andExpect(view().name("post"))
				.andExpect(content().string(containsString("컨실러 협찬 콘텐츠")))
				.andExpect(content().string(containsString("루나")))
				.andExpect(content().string(containsString("계정 평균 수준의 성과")));
	}

	@Test
	void 없는_게시물은_404() throws Exception {
		given(postDetailRepository.find("NOPE")).willReturn(Optional.empty());

		mockMvc.perform(get("/posts/NOPE")).andExpect(status().isNotFound());
	}

	@Test
	void 분석_없는_게시물도_렌더된다() throws Exception {
		Map<String, Object> bare = new HashMap<>();
		bare.put("short_code", "BARE123");
		bare.put("account_handle", "tester");
		bare.put("caption", "미분석 게시물");
		given(postDetailRepository.find("BARE123")).willReturn(Optional.of(bare));
		given(postDetailRepository.commentBreakdown("BARE123")).willReturn(List.of());
		given(postDetailRepository.topComments("BARE123", 5)).willReturn(List.of());

		mockMvc.perform(get("/posts/BARE123"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("미분석 게시물")));
	}
}
