package com.celfit.was.contentlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.config.ClockConfig;
import com.celfit.was.config.SecurityConfig;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Security 도입 후 GET /api/** permitAll을 실제로 결정하는 건 SecurityConfig의 필터체인이라(예전 WebConfig의
// CORS 매핑은 걷어냄) 이 빈을 같이 import해야 401로 막히지 않는다 — 기대값(200/400)은 그대로다.
@WebMvcTest(controllers = ContentListController.class,
		properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({ContentListAssembler.class, ClockConfig.class, SecurityConfig.class})
class ContentListControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	ContentListRepository repository;

	private ContentListRow h1() {
		return new ContentListRow(
				"h1", "https://thumb/h1.jpg", "여름 립케어 루틴",
				OffsetDateTime.parse("2026-06-28T03:00:00Z"), "reels",
				"alpha", "알파", "https://pic/alpha.jpg", 5000L,
				1000L, 100L, 10L, 1000L, OffsetDateTime.parse("2026-07-01T03:00:00Z"),
				"sponsored", "[\"립틴트\"]", 2);
	}

	@Test
	void snake_case_파라미터_전체가_쿼리로_매핑돼_리포지토리에_전달된다() throws Exception {
		given(repository.countContents(any())).willReturn(1L);
		given(repository.findContents(any())).willReturn(List.of(h1()));

		mockMvc.perform(get("/api/contents").with(user("tester"))
						.param("start_date", "2026-06-27")
						.param("end_date", "2026-07-03")
						.param("main_category", "makeup")
						.param("mid_category", "립메이크업")
						.param("sub_category", "립틴트")
						.param("content_type", "reels")
						.param("follower", "3k-10k")
						.param("ad_type", "sponsored")
						.param("distributor", "올리브영")
						.param("q", "립케어")
						.param("sort", "engagement"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalCount").value(1))
				.andExpect(jsonPath("$.items[0].shortCode").value("h1"))
				.andExpect(jsonPath("$.items[0].engagementRate").value(0.11))
				.andExpect(jsonPath("$.items[0].account.handle").value("alpha"))
				.andExpect(jsonPath("$.items[0].productCategories[0]").value("립틴트"))
				.andExpect(jsonPath("$.items[0].brandCount").value(2));

		ArgumentCaptor<ContentListQuery> captor = ArgumentCaptor.forClass(ContentListQuery.class);
		verify(repository).findContents(captor.capture());
		ContentListQuery query = captor.getValue();
		assertThat(query.startInstant()).isEqualTo(OffsetDateTime.parse("2026-06-26T15:00:00Z"));
		assertThat(query.cutoff()).isEqualTo(OffsetDateTime.parse("2026-07-03T15:00:00Z"));
		assertThat(query.mainCategory()).isEqualTo("makeup");
		assertThat(query.midCategory()).isEqualTo("립메이크업");
		assertThat(query.subCategory()).isEqualTo("립틴트");
		assertThat(query.contentType()).isEqualTo("reels");
		assertThat(query.follower()).isEqualTo(ContentListQuery.FollowerRange.R3K_10K);
		assertThat(query.adType()).isEqualTo("sponsored");
		assertThat(query.distributor()).isEqualTo("올리브영");
		assertThat(query.q()).isEqualTo("립케어");
		assertThat(query.sort()).isEqualTo("engagement");
	}

	@Test
	void start_date_누락시_400() throws Exception {
		mockMvc.perform(get("/api/contents").with(user("tester")).param("end_date", "2026-07-03"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void end_date_형식_오류시_400() throws Exception {
		mockMvc.perform(get("/api/contents").with(user("tester"))
						.param("start_date", "2026-06-27")
						.param("end_date", "bad"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 기간만_지정하면_필터_전부_null이고_sort는_hype_기본값이다() throws Exception {
		given(repository.countContents(any())).willReturn(0L);
		given(repository.findContents(any())).willReturn(List.of());

		mockMvc.perform(get("/api/contents").with(user("tester"))
						.param("start_date", "2026-06-27")
						.param("end_date", "2026-07-03"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalCount").value(0))
				.andExpect(jsonPath("$.items").isEmpty());

		ArgumentCaptor<ContentListQuery> captor = ArgumentCaptor.forClass(ContentListQuery.class);
		verify(repository).findContents(captor.capture());
		ContentListQuery query = captor.getValue();
		assertThat(query.mainCategory()).isNull();
		assertThat(query.midCategory()).isNull();
		assertThat(query.subCategory()).isNull();
		assertThat(query.contentType()).isNull();
		assertThat(query.follower()).isNull();
		assertThat(query.adType()).isNull();
		assertThat(query.distributor()).isNull();
		assertThat(query.q()).isNull();
		assertThat(query.sort()).isEqualTo("hype");
	}
}
