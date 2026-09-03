package com.celfit.monitoring.web;

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
 * monitoring.image.backfill-trigger-enabled 기본값(false) 회귀 가드 — 이 프로퍼티를 명시하지 않은
 * 컨텍스트(=운영 기본)에서 {@link AuthorImageBackfillController}가 아예 빈으로 뜨지 않아
 * /api/author-image-backfill이 403·에러가 아니라 404여야 한다(SweepControllerDisabledTest와 동형).
 */
// 백필 재시도 스케줄러 틱 방지(2026-09, 결함 3) — @SpringBootTest 컨텍스트에서 5분 주기가 돌지 않게.
@SpringBootTest(properties = "monitoring.brand.backfill-retry.enabled=false")
class AuthorImageBackfillControllerDisabledTest {

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
	void 게이트_기본값_false에서_POST_author_image_backfill은_404() throws Exception {
		mvc.perform(post("/api/author-image-backfill?limit=100")).andExpect(status().isNotFound());
	}
}
