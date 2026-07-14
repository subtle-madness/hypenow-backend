package com.celfit.was.coverage;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CoverageController.class)
class CoverageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CoverageRepository coverageRepository;

	@MockitoBean
	private com.celfit.was.post.PostDetailRepository postDetailRepository;

	@Test
	void 커버리지_페이지는_타일과_매트릭스를_렌더한다() throws Exception {
		given(coverageRepository.tiles())
				.willReturn(new CoverageTiles(137, 114, 137, LocalDate.of(2026, 7, 11), 2));
		given(coverageRepository.matrix())
				.willReturn(List.of(CoverageRow.of(1, "계정 핸들·이름·프로필", "accounts", "114 / 114", "준비됨")));
		given(postDetailRepository.analyzedPosts())
				.willReturn(List.of(java.util.Map.of("short_code", "CU2HcR4FYzW", "account_handle", "tester",
						"classified", 30L)));

		mockMvc.perform(get("/coverage"))
				.andExpect(status().isOk())
				.andExpect(view().name("coverage"))
				.andExpect(model().attributeExists("tiles", "matrix"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("계정 핸들·이름·프로필")));
	}

	@Test
	void DB_조회가_비어도_페이지는_렌더된다() throws Exception {
		given(coverageRepository.tiles()).willReturn(null);
		given(coverageRepository.matrix()).willReturn(List.of());

		mockMvc.perform(get("/coverage"))
				.andExpect(status().isOk())
				.andExpect(view().name("coverage"));
	}

	@Test
	void 상태_문구가_등급으로_매핑된다() {
		org.assertj.core.api.Assertions.assertThat(CoverageRow.of(1, "e", "s", "1 / 1", "준비됨").grade()).isEqualTo("ok");
		org.assertj.core.api.Assertions.assertThat(CoverageRow.of(2, "e", "s", "1 / 2", "일부 누락").grade()).isEqualTo("warn");
		org.assertj.core.api.Assertions.assertThat(CoverageRow.of(3, "e", "s", "1 / 67", "정상 범위").grade()).isEqualTo("ok");
		org.assertj.core.api.Assertions.assertThat(CoverageRow.of(4, "e", "s", "0 / 137", "없음").grade()).isEqualTo("bad");
		org.assertj.core.api.Assertions.assertThat(CoverageRow.of(5, "e", "s", "3 / 137", "부분").grade()).isEqualTo("warn");
		org.assertj.core.api.Assertions.assertThat(CoverageRow.of(6, "e", "s", "2행", "옛 산출물 — 태스크 A 재구축 대상").grade()).isEqualTo("warn");
		org.assertj.core.api.Assertions.assertThat(CoverageRow.of(7, "e", "s", "0", "없음 — 분석 뷰에서 계산 필요").grade()).isEqualTo("bad");
	}
}
