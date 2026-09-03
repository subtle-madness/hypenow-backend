package com.celfit.monitoring.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.monitoring.testsupport.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * monitoring.sweep.manual-trigger-enabled 기본값(false) 회귀 가드 — 이 프로퍼티를 명시하지 않은
 * 컨텍스트(=운영 기본)에서 SweepController가 아예 빈으로 뜨지 않아 /api/sweeps*가 403·에러가 아니라
 * 404여야 한다({@link SweepController} 클래스 javadoc 참고). POST(수동 트리거 — Hiker 콜 증폭 지점)와
 * GET /latest(단순 조회) 둘 다 같은 컨트롤러에 묶여 함께 꺼지므로 같이 검증한다.
 */
// 백필 재시도 스케줄러 틱 방지(2026-09, 결함 3) — @SpringBootTest 컨텍스트에서 5분 주기가 돌지 않게.
@SpringBootTest(properties = "monitoring.brand.backfill-retry.enabled=false")
class SweepControllerDisabledTest {

	@DynamicPropertySource
	static void dbProps(DynamicPropertyRegistry r) {
		var pg = TestDb.container();
		r.add("spring.datasource.url", pg::getJdbcUrl);
		r.add("spring.datasource.username", pg::getUsername);
		r.add("spring.datasource.password", pg::getPassword);
	}

	@Autowired WebApplicationContext ctx;
	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
	}

	@Test
	void 게이트_기본값_false에서_POST_sweeps는_404() throws Exception {
		mvc.perform(post("/api/sweeps")).andExpect(status().isNotFound());
	}

	@Test
	void 게이트_기본값_false에서_GET_sweeps_latest도_404() throws Exception {
		mvc.perform(get("/api/sweeps/latest")).andExpect(status().isNotFound());
	}
}
