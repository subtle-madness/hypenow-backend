package com.celfit.was.v1.notices;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.nullValue;

import com.celfit.was.IntegrationTest;
import com.celfit.was.V1AuthTestSteps;
import com.celfit.was.notices.NoticeRepository;
import com.celfit.was.notices.NoticeRepository.ItemInput;
import com.celfit.was.v1.common.KstTimestamps;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 유저 표면 업데이트 소식 API(GET /v1/notices, GET·PUT /v1/notices/seen) — 실 DB로 정렬·KST 파생·
 * 예약 발행 제외·명시적 null·seen 왕복을 검증한다. app.notices·notice_items·notice_seen은 이 테스트
 * 클래스 전용 테이블이라 각 테스트 전에 TRUNCATE로 격리한다(다른 테스트 클래스는 건드리지 않음).
 */
@AutoConfigureMockMvc
class V1NoticesIntegrationTest extends IntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	NoticeRepository noticeRepository;

	@BeforeEach
	void setUp() {
		V1AuthTestSteps.enableSignupCode(jdbcClient);
		jdbcClient.sql("TRUNCATE app.notices RESTART IDENTITY CASCADE").update();
		jdbcClient.sql("TRUNCATE app.notice_seen").update();
	}

	private Cookie signUpAndLogIn(String email) throws Exception {
		return V1AuthTestSteps.signUp(mockMvc, jdbcClient, email);
	}

	@Test
	void 발행된_소식이_내림차순_정렬로_items는_position_순서로_온다() throws Exception {
		OffsetDateTime older = OffsetDateTime.now().minusDays(2);
		OffsetDateTime newer = OffsetDateTime.now().minusDays(1);
		noticeRepository.insert("먼저 발행", older, List.of(
				new ItemInput("new", "요약1", "본문1", null, null)));
		long newerId = noticeRepository.insert("나중 발행", newer, List.of(
				new ItemInput("changed", "요약A", "본문A", "https://a", "라벨A"),
				new ItemInput("fixed", "요약B", "본문B", null, null)));

		mockMvc.perform(get("/v1/notices").cookie(signUpAndLogIn("notices-order@example.com")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.meta.total").value(2))
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.data[0].id").value(String.valueOf(newerId)))
				.andExpect(jsonPath("$.data[0].title").value("나중 발행"))
				.andExpect(jsonPath("$.data[0].date").value(KstTimestamps.toKstDate(newer).toString()))
				.andExpect(jsonPath("$.data[0].publishedAt").value(KstTimestamps.toKstIso(newer)))
				.andExpect(jsonPath("$.data[0].items.length()").value(2))
				.andExpect(jsonPath("$.data[0].items[0].tag").value("changed"))
				.andExpect(jsonPath("$.data[0].items[0].summary").value("요약A"))
				.andExpect(jsonPath("$.data[0].items[0].link.href").value("https://a"))
				.andExpect(jsonPath("$.data[0].items[0].link.label").value("라벨A"))
				.andExpect(jsonPath("$.data[0].items[1].tag").value("fixed"))
				.andExpect(jsonPath("$.data[1].title").value("먼저 발행"));
	}

	@Test
	void link_없는_항목은_응답에_link_키가_명시적_null로_존재한다() throws Exception {
		noticeRepository.insert("링크 없음", OffsetDateTime.now().minusHours(1), List.of(
				new ItemInput("improved", "요약", "본문", null, null)));

		mockMvc.perform(get("/v1/notices").cookie(signUpAndLogIn("notices-nulllink@example.com")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].items[0]").isMap())
				// jsonPath(...).exists()는 이 프로젝트 JsonPath 설정 기본값(DEFAULT_PATH_LEAF_TO_NULL
				// 미설정)에서 JSON null 리프를 "없음"으로 취급해 실패한다(2026-08-03 실측) — .value(nullValue())
				// 단독으로도 키가 없으면 PathNotFoundException으로 실패하므로 "명시적 null 키 존재" 검증엔 충분하다.
				.andExpect(jsonPath("$.data[0].items[0].link").value(nullValue()));
	}

	@Test
	void 미래_발행_소식은_유저_목록에서_제외된다() throws Exception {
		noticeRepository.insert("과거 발행", OffsetDateTime.now().minusDays(1), List.of(
				new ItemInput("new", "요약", "본문", null, null)));
		noticeRepository.insert("예약 발행", OffsetDateTime.now().plusDays(3), List.of(
				new ItemInput("new", "요약", "본문", null, null)));

		mockMvc.perform(get("/v1/notices").cookie(signUpAndLogIn("notices-future@example.com")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meta.total").value(1))
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].title").value("과거 발행"));
	}

	@Test
	void 발행된_소식이_없으면_빈_배열이다() throws Exception {
		mockMvc.perform(get("/v1/notices").cookie(signUpAndLogIn("notices-empty@example.com")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.meta.total").value(0))
				.andExpect(jsonPath("$.data.length()").value(0));
	}

	@Test
	void 비로그인_목록_조회는_401이다() throws Exception {
		mockMvc.perform(get("/v1/notices"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void seen_최초_조회는_null이고_PUT_이후_반영되며_갱신도_저장되고_불량_요청은_400이다() throws Exception {
		Cookie session = signUpAndLogIn("notices-seen@example.com");

		mockMvc.perform(get("/v1/notices/seen").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.lastSeenAt").value(nullValue()));

		mockMvc.perform(put("/v1/notices/seen").with(csrf()).cookie(session)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"lastSeenAt\":\"2026-08-03T10:05:00+09:00\"}"))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/v1/notices/seen").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.lastSeenAt").value("2026-08-03T10:05:00+09:00"));

		mockMvc.perform(put("/v1/notices/seen").with(csrf()).cookie(session)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"lastSeenAt\":\"2026-08-04T09:30:00+09:00\"}"))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/v1/notices/seen").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.lastSeenAt").value("2026-08-04T09:30:00+09:00"));

		mockMvc.perform(put("/v1/notices/seen").with(csrf()).cookie(session)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"lastSeenAt\":\"not-a-date\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void seen_비로그인_GET_PUT_모두_401이다() throws Exception {
		mockMvc.perform(get("/v1/notices/seen"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(put("/v1/notices/seen").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"lastSeenAt\":\"2026-08-03T10:05:00+09:00\"}"))
				.andExpect(status().isUnauthorized());
	}
}
