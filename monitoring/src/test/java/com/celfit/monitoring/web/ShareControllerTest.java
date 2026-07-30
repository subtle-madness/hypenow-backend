package com.celfit.monitoring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.monitoring.hiker.HikerBadRequestException;
import com.celfit.monitoring.hiker.HikerFetchException;
import com.celfit.monitoring.hiker.HikerHttp;
import com.celfit.monitoring.hiker.SubjectNotFoundException;
import com.celfit.monitoring.testsupport.TestDb;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * share 단축 링크 해소 API — 계약 §2-6. RegistrationApiTest의 SwitchableHiker 패턴을 이 컨트롤러
 * 전용으로 축소한 fake를 쓴다(등록 플로우와 무관한 별도 API라 그쪽 fake를 재사용할 이유가 없다).
 */
@SpringBootTest
class ShareControllerTest {

	static class FakeShareHiker implements HikerHttp {
		volatile boolean notFound;
		volatile boolean badRequest;
		volatile boolean broken;

		private static String fixture(String name) {
			try (var in = ShareControllerTest.class.getResourceAsStream("/hiker/" + name)) {
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		@Override
		public String get(String path) {
			if (notFound) {
				throw new SubjectNotFoundException("404");
			}
			if (badRequest) {
				throw new HikerBadRequestException("400");
			}
			if (broken) {
				throw new HikerFetchException("500");
			}
			return fixture("media-info-by-url.json");
		}
	}

	@TestConfiguration
	static class Fakes {
		@Bean
		@Primary
		FakeShareHiker fakeShareHiker() {
			return new FakeShareHiker();
		}
	}

	@DynamicPropertySource
	static void dbProps(DynamicPropertyRegistry r) {
		var pg = TestDb.container();
		r.add("spring.datasource.url", pg::getJdbcUrl);
		r.add("spring.datasource.username", pg::getUsername);
		r.add("spring.datasource.password", pg::getPassword);
	}

	@Autowired WebApplicationContext ctx;
	@Autowired FakeShareHiker hiker;
	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
		hiker.notFound = false;
		hiker.badRequest = false;
		hiker.broken = false;
	}

	@Test
	void 정상_URL은_shortCode_username_contentType을_돌려준다() throws Exception {
		mvc.perform(post("/api/share/resolve")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"url\":\"https://www.instagram.com/reel/DbV7LgZsKG8/\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shortCode").value("DbV7LgZsKG8"))
				.andExpect(jsonPath("$.username").value("sephora"))
				.andExpect(jsonPath("$.contentType").value("REELS"));
	}

	/** instagram.com 계열이 아닌 URL은 Hiker를 부르지 않고 즉시 422로 선차단한다(콜 절약). */
	@Test
	void instagram이_아닌_URL은_Hiker_호출_없이_422로_선차단() throws Exception {
		mvc.perform(post("/api/share/resolve")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"url\":\"https://example.com/foo\"}"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("SHARE_LINK_UNRESOLVED"));
	}

	@Test
	void 빈_URL은_422_SHARE_LINK_UNRESOLVED() throws Exception {
		mvc.perform(post("/api/share/resolve")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"url\":\"\"}"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("SHARE_LINK_UNRESOLVED"));
	}

	@Test
	void Hiker_400은_422_SHARE_LINK_UNRESOLVED() throws Exception {
		hiker.badRequest = true;
		mvc.perform(post("/api/share/resolve")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"url\":\"https://www.instagram.com/reel/bad/\"}"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("SHARE_LINK_UNRESOLVED"));
	}

	@Test
	void Hiker_404는_404_SUBJECT_NOT_FOUND() throws Exception {
		hiker.notFound = true;
		mvc.perform(post("/api/share/resolve")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"url\":\"https://www.instagram.com/reel/gone/\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
	}

	@Test
	void Hiker_5xx는_502_FETCH_FAILED() throws Exception {
		hiker.broken = true;
		mvc.perform(post("/api/share/resolve")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"url\":\"https://www.instagram.com/reel/oops/\"}"))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("FETCH_FAILED"));
	}
}
