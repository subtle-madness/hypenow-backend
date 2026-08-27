package com.celfit.was.v1.monitoring;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.monitoring.DigestRepository;
import com.celfit.was.monitoring.DigestRow;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * /v1/notifications GET·POST(스펙 6.32) — 응답 형태·최근 30건 상한과 meta.total 불일치 허용
 * (1.4 두 예외 중 하나)·readAt 명시적 null·date 포맷, 읽음 처리의 ids/all 분기와 400 조건을
 * 컨트롤러 슬라이스로 검증한다. 정렬·limit·소유 격리·멱등 자체는 DigestRepositoryTest가 이미
 * 커버하므로 여기서는 리포지토리를 목킹해 컨트롤러 로직만 본다.
 */
@WebMvcTest(controllers = V1NotificationsController.class, properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({DigestAssembler.class, V1ExceptionAdvice.class, SecurityConfig.class})
class V1NotificationsControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	DigestRepository repository;

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	private static DigestRow row(long id, LocalDate date, OffsetDateTime createdAt, OffsetDateTime readAt,
			String itemsJson) {
		return new DigestRow(id, 7L, date, itemsJson, createdAt, readAt);
	}

	@Test
	void 응답_형태와_items를_그대로_반환한다() throws Exception {
		String itemsJson = """
				[{"category":"content","type":"collection_started","summary":"새로 수집을 시작한 콘텐츠가 있어요","count":2}]
				""";
		DigestRow digest = row(1L, LocalDate.of(2026, 7, 29), OffsetDateTime.parse("2026-07-29T00:00:00Z"),
				null, itemsJson);
		given(repository.findRecentByUser(eq(7L), eq(30))).willReturn(List.of(digest));
		given(repository.countByUser(7L)).willReturn(1L);

		mockMvc.perform(get("/v1/notifications").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].id").value("1"))
				.andExpect(jsonPath("$.data[0].date").value("2026-07-29"))
				.andExpect(jsonPath("$.data[0].items.length()").value(1))
				.andExpect(jsonPath("$.data[0].items[0].category").value("content"))
				.andExpect(jsonPath("$.data[0].items[0].type").value("collection_started"))
				.andExpect(jsonPath("$.data[0].items[0].summary").value("새로 수집을 시작한 콘텐츠가 있어요"))
				.andExpect(jsonPath("$.data[0].items[0].count").value(2))
				.andExpect(jsonPath("$.meta.total").value(1));
	}

	@Test
	void createdAt은_KST_오프셋_09_00_문자열이다() throws Exception {
		DigestRow digest = row(1L, LocalDate.of(2026, 7, 29), OffsetDateTime.parse("2026-07-29T00:00:00Z"),
				null, "[]");
		given(repository.findRecentByUser(eq(7L), eq(30))).willReturn(List.of(digest));
		given(repository.countByUser(7L)).willReturn(1L);

		// UTC 2026-07-29T00:00:00Z → KST(+9h) 2026-07-29T09:00:00+09:00
		mockMvc.perform(get("/v1/notifications").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].createdAt").value("2026-07-29T09:00:00+09:00"));
	}

	@Test
	void readAt_키는_안읽음이면_명시적_null이다() throws Exception {
		DigestRow digest = row(1L, LocalDate.of(2026, 7, 29), OffsetDateTime.parse("2026-07-29T00:00:00Z"),
				null, "[]");
		given(repository.findRecentByUser(eq(7L), eq(30))).willReturn(List.of(digest));
		given(repository.countByUser(7L)).willReturn(1L);

		mockMvc.perform(get("/v1/notifications").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0]", Matchers.hasKey("readAt")))
				.andExpect(jsonPath("$.data[0].readAt").value(Matchers.nullValue()));
	}

	@Test
	void readAt이_값이면_KST_오프셋으로_직렬화된다() throws Exception {
		DigestRow digest = row(1L, LocalDate.of(2026, 7, 29), OffsetDateTime.parse("2026-07-29T00:00:00Z"),
				OffsetDateTime.parse("2026-07-29T01:00:00Z"), "[]");
		given(repository.findRecentByUser(eq(7L), eq(30))).willReturn(List.of(digest));
		given(repository.countByUser(7L)).willReturn(1L);

		mockMvc.perform(get("/v1/notifications").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].readAt").value("2026-07-29T10:00:00+09:00"));
	}

	@Test
	void meta_total은_최근_30건_창과_무관하게_전체_건수다() throws Exception {
		List<DigestRow> thirtyRows = IntStream.rangeClosed(1, 30)
				.mapToObj(i -> row(i, LocalDate.of(2026, 1, 1).plusDays(i), OffsetDateTime.parse("2026-07-29T00:00:00Z"),
						null, "[]"))
				.toList();
		given(repository.findRecentByUser(eq(7L), eq(30))).willReturn(thirtyRows);
		given(repository.countByUser(7L)).willReturn(45L);

		mockMvc.perform(get("/v1/notifications").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(30))
				.andExpect(jsonPath("$.meta.total").value(45));
	}

	@Test
	void 비로그인_GET은_401이다() throws Exception {
		mockMvc.perform(get("/v1/notifications"))
				.andExpect(status().isUnauthorized());

		then(repository).shouldHaveNoInteractions();
	}

	@Test
	void POST_ids로_읽음_처리하면_204() throws Exception {
		mockMvc.perform(post("/v1/notifications/read").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ids\":[\"1\",\"2\"]}"))
				.andExpect(status().isNoContent());

		then(repository).should().markRead(eq(7L), eq(List.of(1L, 2L)));
	}

	@Test
	void POST_all_true면_204이고_창_제한_없이_전체_처리한다() throws Exception {
		mockMvc.perform(post("/v1/notifications/read").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"all\":true}"))
				.andExpect(status().isNoContent());

		then(repository).should().markAllRead(7L);
		then(repository).should(never()).markRead(anyLong(), anyList());
	}

	@Test
	void POST_ids와_all_둘_다_없으면_400() throws Exception {
		mockMvc.perform(post("/v1/notifications/read").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(repository).shouldHaveNoInteractions();
	}

	@Test
	void POST_all_false만_오면_400() throws Exception {
		mockMvc.perform(post("/v1/notifications/read").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"all\":false}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		then(repository).shouldHaveNoInteractions();
	}

	@Test
	void POST_숫자가_아닌_id는_무시하고_204() throws Exception {
		mockMvc.perform(post("/v1/notifications/read").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ids\":[\"abc\",\"5\"]}"))
				.andExpect(status().isNoContent());

		then(repository).should().markRead(eq(7L), eq(List.of(5L)));
	}

	@Test
	void POST_전_원소가_숫자가_아닌_ids면_no_op_204() throws Exception {
		mockMvc.perform(post("/v1/notifications/read").with(user(principal())).with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ids\":[\"abc\",\"def\"]}"))
				.andExpect(status().isNoContent());

		then(repository).should().markRead(eq(7L), eq(List.of()));
	}

	@Test
	void 비로그인_POST는_401이다() throws Exception {
		mockMvc.perform(post("/v1/notifications/read").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"all\":true}"))
				.andExpect(status().isUnauthorized());

		then(repository).shouldHaveNoInteractions();
	}

	@Test
	void 주간_항목의_metrics가_응답에_그대로_실린다() throws Exception {
		String itemsJson = """
				[{"category":"brand","type":"brand_new_posts","summary":"브랜드를 언급한 새 게시물을 찾았어요",\
				"count":12,"metrics":{"views":123456,"likes":7890,"comments":123}}]""";
		given(repository.findRecentByUser(eq(7L), eq(30))).willReturn(List.of(
				row(1L, LocalDate.of(2026, 8, 17), OffsetDateTime.parse("2026-08-24T00:00:00Z"), null, itemsJson)));
		given(repository.countByUser(7L)).willReturn(1L);

		mockMvc.perform(get("/v1/notifications").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].date").value("2026-08-17"))
				.andExpect(jsonPath("$.data[0].items[0].category").value("brand"))
				.andExpect(jsonPath("$.data[0].items[0].type").value("brand_new_posts"))
				.andExpect(jsonPath("$.data[0].items[0].count").value(12))
				.andExpect(jsonPath("$.data[0].items[0].metrics.views").value(123456))
				.andExpect(jsonPath("$.data[0].items[0].metrics.likes").value(7890))
				.andExpect(jsonPath("$.data[0].items[0].metrics.comments").value(123));
	}

	@Test
	void metrics가_없는_기존_일일_항목도_그대로_읽힌다() throws Exception {
		// 개편 전에 쌓인 일일 다이제스트 행은 metrics 키가 없다 - 히스토리로 보존되므로(설계 §7)
		// 응답 조립이 깨지면 안 된다.
		String itemsJson = """
				[{"category":"content","type":"collection_started","summary":"새로 수집을 시작한 콘텐츠가 있어요","count":2}]""";
		given(repository.findRecentByUser(eq(7L), eq(30))).willReturn(List.of(
				row(1L, LocalDate.of(2026, 7, 30), OffsetDateTime.parse("2026-07-30T00:00:00Z"), null, itemsJson)));
		given(repository.countByUser(7L)).willReturn(1L);

		mockMvc.perform(get("/v1/notifications").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].items[0].count").value(2))
				.andExpect(jsonPath("$.data[0].items[0].metrics").doesNotExist());
	}

	@Test
	void metrics의_null_지표는_키를_유지한_채_내려간다() throws Exception {
		// 조회수가 그 주에 전부 NULL이면 views는 null이다 - 키를 지우면 프론트가 0과 구분하지 못한다.
		String itemsJson = """
				[{"category":"brand","type":"brand_new_posts","summary":"브랜드를 언급한 새 게시물을 찾았어요",\
				"count":3,"metrics":{"views":null,"likes":30,"comments":3}}]""";
		given(repository.findRecentByUser(eq(7L), eq(30))).willReturn(List.of(
				row(1L, LocalDate.of(2026, 8, 17), OffsetDateTime.parse("2026-08-24T00:00:00Z"), null, itemsJson)));
		given(repository.countByUser(7L)).willReturn(1L);

		mockMvc.perform(get("/v1/notifications").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].items[0].metrics.views").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.data[0].items[0].metrics").exists())
				.andExpect(jsonPath("$.data[0].items[0].metrics.likes").value(30));
	}
}
