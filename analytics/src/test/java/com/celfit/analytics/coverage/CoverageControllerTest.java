package com.celfit.analytics.coverage;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CoverageController.class)
@TestPropertySource(properties = "analytics.admin-enabled=true") // 컨트롤러의 @ConditionalOnProperty 게이트
class CoverageControllerTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	CoverageRepository coverageRepository;

	@Test
	void 커버리지_페이지는_모수_타일과_매트릭스를_렌더한다() throws Exception {
		given(coverageRepository.source()).willReturn(new CoverageSource(1496, 20000));
		given(coverageRepository.tiles())
				.willReturn(new CoverageTiles(16686, 12638, 88));
		given(coverageRepository.matrix())
				.willReturn(List.of(CoverageRow.of(1, "계정 핸들·이름·프로필", "accounts", "12638 / 12638", "준비됨")));

		mvc.perform(get("/ui/coverage"))
				.andExpect(status().isOk())
				.andExpect(view().name("coverage"))
				.andExpect(model().attributeExists("source", "tiles", "matrix"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("계정 핸들·이름·프로필")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("1,496")));
	}

	@Test
	void DB_조회가_비어도_페이지는_렌더된다() throws Exception {
		// raw·analysis 어느 쪽이 죽어도 어드민 화면은 떠야 한다 (어드민 UI 관용구)
		given(coverageRepository.source()).willReturn(null);
		given(coverageRepository.tiles()).willReturn(null);
		given(coverageRepository.matrix()).willReturn(List.of());

		mvc.perform(get("/ui/coverage"))
				.andExpect(status().isOk())
				.andExpect(view().name("coverage"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("조회 실패")));
	}
}
