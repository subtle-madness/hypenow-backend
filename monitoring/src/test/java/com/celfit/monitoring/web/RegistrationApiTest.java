package com.celfit.monitoring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class RegistrationApiTest {

	/** 경로별 픽스처 fake — 열거는 clips(조회수 머지) → medias 두 콜을 쏜다. notFound=true면 전 경로 404. */
	static class SwitchableHiker implements HikerHttp {
		volatile boolean notFound = false;
		volatile boolean privateAccount = false;
		volatile boolean postWithoutOwner = false;

		/** 단건 응답에서 user만 빠진 변형 — 소유 계정을 알 수 없어 등록도 스냅샷 적재도 불가한 셰이프. */
		private static final String POST_WITHOUT_OWNER = """
				{"num_results":1,"items":[{"code":"DbV7LgZsKG8","product_type":"clips",
				"taken_at":1785254651,"like_count":1,"comment_count":1,"media_repost_count":1}]}""";

		private static String fixture(String name) {
			try (var in = RegistrationApiTest.class.getResourceAsStream("/hiker/" + name)) {
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
			if (path.startsWith("/v2/user/by/username")) {
				return privateAccount ? "{\"user\":{\"pk\":1,\"is_private\":true},\"status\":\"ok\"}"
						: fixture("profile.json");
			}
			if (path.startsWith("/v2/user/medias")) {
				return fixture("medias.json");
			}
			if (path.startsWith("/v2/user/clips")) {
				return fixture("clips.json");
			}
			return postWithoutOwner ? POST_WITHOUT_OWNER : fixture("media-by-code.json");
		}
	}

	@TestConfiguration
	static class Fakes {
		@Bean
		@Primary
		SwitchableHiker fakeHiker() {
			return new SwitchableHiker();
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
	@Autowired JdbcTemplate db;
	@Autowired SwitchableHiker hiker;
	MockMvc mvc;

	private static final String ACCOUNT_BODY = """
			{"registrationKey":"rk-1","type":"ACCOUNT","username":"someuser",
			 "keywordRule":{"and":[],"any":["샤넬"],"exclude":[]},
			 "expiresAt":"2027-01-01T00:00:00+09:00"}""";

	private static final String POST_BODY = """
			{"registrationKey":"rk-post","type":"POST","shortCode":"DbV7LgZsKG8",
			 "expiresAt":"2027-01-01T00:00:00+09:00"}""";

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
		db.update("DELETE FROM detected_candidate");
		db.update("DELETE FROM target");
		// 스냅샷·원형도 비운다 — 다른 테스트 클래스(StoreTest)가 남긴 행이 섞이면
		// 아래 절대값 단언(1행·2행)이 실행 순서에 따라 흔들린다.
		db.update("DELETE FROM profile_snapshot");
		db.update("DELETE FROM post_snapshot");
		db.update("DELETE FROM raw.fetch_payload");
		hiker.notFound = false;
		hiker.privateAccount = false;
		hiker.postWithoutOwner = false;
	}

	@Test
	void 계정_등록은_동기_첫_수집까지_하고_201() throws Exception {
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("WATCHING"))
				.andExpect(jsonPath("$.firstSnapshot.profile.followers").isNumber());
		assertThat(db.queryForObject("SELECT count(*) FROM profile_snapshot", Long.class)).isEqualTo(1);
		// 원형은 콜 단위로 남는다 — 프로필 1콜 + 열거 2콜(clips 조회수 보강 + medias).
		// 파싱 결과(PostInfo.rawJson)를 저장하던 시절엔 clips 응답이 통째로 감사에서 빠졌다.
		assertThat(db.queryForObject("""
				SELECT count(*) FROM raw.fetch_payload WHERE kind='PROFILE' AND subject='someuser'""",
				Long.class)).isEqualTo(1);
		assertThat(db.queryForObject("""
				SELECT count(*) FROM raw.fetch_payload WHERE kind='POSTS'""", Long.class)).isEqualTo(2);
		// 키워드 규칙은 was가 조회 표면에서 그대로 읽는 컬럼이다 — 요청 그대로 jsonb에 실려야 한다
		assertThat(db.queryForObject("""
				SELECT keyword_rule ->> 'any' FROM target WHERE registration_key='rk-1'""", String.class))
				.contains("샤넬");
		// +09:00 오프셋이 UTC로 옮겨져 저장되는지 — 여기가 틀리면 만료 스윕이 9시간 어긋난다
		assertThat(db.queryForObject("""
				SELECT expires_at = timestamptz '2026-12-31T15:00:00Z' FROM target
				WHERE registration_key='rk-1'""", Boolean.class)).isTrue();
		assertThat(db.queryForObject("""
				SELECT last_fetched_at IS NOT NULL FROM target WHERE registration_key='rk-1'""",
				Boolean.class)).isTrue();
	}

	@Test
	void 같은_registrationKey는_replay_200_행_1개_재수집_없음() throws Exception {
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isCreated());
		Long rawAfterFirst = db.queryForObject("SELECT count(*) FROM raw.fetch_payload", Long.class);

		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isOk())
				// replay는 Hiker를 다시 부르지 않으므로 돌려줄 첫 스냅샷이 없다(계약 §2-1).
				// jsonPath().doesNotExist()는 `"firstSnapshot":null`도 통과시키므로(JsonPath가 null을 반환)
				// 키가 정말 빠졌는지는 본문 문자열로 못박는다.
				.andExpect(jsonPath("$.firstSnapshot").doesNotExist())
				.andExpect(content().string(not(containsString("firstSnapshot"))));
		assertThat(db.queryForObject("SELECT count(*) FROM target", Long.class)).isEqualTo(1);
		// 원형 적재 행이 안 늘었다 = 콜이 안 나갔다. replay가 조용히 재수집하면 재시도마다 과금된다.
		assertThat(db.queryForObject("SELECT count(*) FROM raw.fetch_payload", Long.class))
				.isEqualTo(rawAfterFirst);
	}

	@Test
	void 키워드_and_any_모두_비면_400_VALIDATION() throws Exception {
		String bad = ACCOUNT_BODY.replace("\"any\":[\"샤넬\"]", "\"any\":[]");
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(bad))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION"));
	}

	@Test
	void 계정_없음은_404_SUBJECT_NOT_FOUND_target_미생성() throws Exception {
		hiker.notFound = true;
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
		assertThat(db.queryForObject("SELECT count(*) FROM target", Long.class)).isZero();
	}

	/** 비공개는 "없음"과 다르다 — 계정은 실재하지만 열거가 막힌 것이라 422로 구분해 내려야 한다. */
	@Test
	void 비공개_계정은_422_PRIVATE_ACCOUNT_target_미생성() throws Exception {
		hiker.privateAccount = true;
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_BODY))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("PRIVATE_ACCOUNT"));
		assertThat(db.queryForObject("SELECT count(*) FROM target", Long.class)).isZero();
	}

	/** 소유 계정 없는 단건 응답은 셰이프 이상이다 — DB NOT NULL 위반(500)이 아니라 502로 나가야 한다. */
	@Test
	void 소유_계정_없는_게시물_응답은_502_FETCH_FAILED() throws Exception {
		hiker.postWithoutOwner = true;
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(POST_BODY))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("FETCH_FAILED"));
		assertThat(db.queryForObject("SELECT count(*) FROM target", Long.class)).isZero();
	}

	/** 캐치올 advice가 프레임워크 4xx를 500으로 강등하면 배선 오류를 장애로 오인하게 된다. */
	@Test
	void 없는_경로는_500이_아니라_404로_남는다() throws Exception {
		mvc.perform(post("/api/none")
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNotFound());
	}

	/** POST 등록은 승인 절차가 없다 — 등록 즉시 TRACKING이고 추적 게시물이 곧 등록한 숏코드다. */
	@Test
	void 게시물_등록은_바로_TRACKING이고_post_snapshot을_남긴다() throws Exception {
		mvc.perform(post("/api/targets")
				.contentType(MediaType.APPLICATION_JSON).content(POST_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("TRACKING"))
				.andExpect(jsonPath("$.firstSnapshot.post.likes").isNumber());
		assertThat(db.queryForObject("""
				SELECT tracked_short_code FROM target WHERE registration_key='rk-post'""", String.class))
				.isEqualTo("DbV7LgZsKG8");
		// POST 등록도 소유 계정을 기록한다(계약 §3 target.username — 사용자가 준 건 숏코드뿐이라 응답에서 얻는다)
		assertThat(db.queryForObject("""
				SELECT username FROM target WHERE registration_key='rk-post'""", String.class))
				.isEqualTo("sephora");
		assertThat(db.queryForObject("""
				SELECT count(*) FROM post_snapshot WHERE short_code='DbV7LgZsKG8'""", Long.class))
				.isEqualTo(1);
	}
}
