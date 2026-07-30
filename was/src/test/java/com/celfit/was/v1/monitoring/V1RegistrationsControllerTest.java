package com.celfit.was.v1.monitoring;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.monitoring.RegistrationEntryRow;
import com.celfit.was.monitoring.RegistrationRepository;
import com.celfit.was.monitoring.RegistrationRow;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * /v1/monitoring/registrations GET(스펙 6.28) — 응답 형태·최근 50건 상한과 meta.total 불일치
 * 허용(1.4 두 예외 중 하나)·nullable 필드 명시적 null·KST 오프셋·itemId 문자열화를 컨트롤러
 * 슬라이스로 검증한다. 정렬·entries seq 순서·limit 동작 자체는 RegistrationRepositoryTest가
 * 이미 커버하므로 여기서는 리포지토리가 준 결과를 그대로 응답으로 옮기는지만 본다.
 */
@WebMvcTest(controllers = V1RegistrationsController.class, properties = "was.cors.allowed-origins=http://localhost:3000")
@Import({V1ExceptionAdvice.class, SecurityConfig.class})
class V1RegistrationsControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	RegistrationRepository repository;

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	private static RegistrationEntryRow entry(long registrationId, int seq, String input, String kind,
			String result, String reasonCode, String reason, String resolvedUrl, Long itemId) {
		return new RegistrationEntryRow(registrationId, seq, input, kind, result, reasonCode, reason, resolvedUrl,
				itemId);
	}

	@Test
	void 응답_형태와_entries_순서를_그대로_반환한다() throws Exception {
		RegistrationRow row = new RegistrationRow(1L, 7L, OffsetDateTime.parse("2026-07-29T00:00:00Z"),
				OffsetDateTime.parse("2026-07-29T00:05:00Z"),
				List.of(
						entry(1L, 1, "https://www.instagram.com/p/ABC123/", "post", "success", null, null, null, 200L),
						entry(1L, 2, "glowdeep", "account", "duplicate", "duplicate", "이미 진행 중인 대상이에요.", null, null)));
		given(repository.findRecentByUser(eq(7L), eq(50))).willReturn(List.of(row));
		given(repository.countByUser(7L)).willReturn(1L);

		mockMvc.perform(get("/v1/monitoring/registrations").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].id").value("1"))
				.andExpect(jsonPath("$.data[0].entries.length()").value(2))
				.andExpect(jsonPath("$.data[0].entries[0].input").value("https://www.instagram.com/p/ABC123/"))
				.andExpect(jsonPath("$.data[0].entries[0].result").value("success"))
				.andExpect(jsonPath("$.data[0].entries[1].input").value("glowdeep"))
				.andExpect(jsonPath("$.data[0].entries[1].result").value("duplicate"))
				.andExpect(jsonPath("$.meta.total").value(1));
	}

	@Test
	void requestedAt은_KST_오프셋_09_00_문자열이다() throws Exception {
		RegistrationRow row = new RegistrationRow(1L, 7L, OffsetDateTime.parse("2026-07-29T00:00:00Z"), null,
				List.of());
		given(repository.findRecentByUser(eq(7L), eq(50))).willReturn(List.of(row));
		given(repository.countByUser(7L)).willReturn(1L);

		// UTC 2026-07-29T00:00:00Z → KST(+9h) 2026-07-29T09:00:00+09:00
		mockMvc.perform(get("/v1/monitoring/registrations").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].requestedAt").value("2026-07-29T09:00:00+09:00"));
	}

	@Test
	void nullable_필드_5종은_키가_존재하며_명시적으로_null이다() throws Exception {
		RegistrationRow row = new RegistrationRow(1L, 7L, OffsetDateTime.parse("2026-07-29T00:00:00Z"), null,
				List.of(entry(1L, 1, "https://www.instagram.com/p/ABC123/", "post", "pending", null, null, null,
						null)));
		given(repository.findRecentByUser(eq(7L), eq(50))).willReturn(List.of(row));
		given(repository.countByUser(7L)).willReturn(1L);

		mockMvc.perform(get("/v1/monitoring/registrations").with(user(principal())))
				.andExpect(status().isOk())
				// completedAt(진행 중)
				.andExpect(jsonPath("$.data[0]", Matchers.hasKey("completedAt")))
				.andExpect(jsonPath("$.data[0].completedAt").value(Matchers.nullValue()))
				// entries[].reason·reasonCode·resolvedUrl·itemId(성공 대기 중)
				.andExpect(jsonPath("$.data[0].entries[0]", Matchers.hasKey("reason")))
				.andExpect(jsonPath("$.data[0].entries[0].reason").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.data[0].entries[0]", Matchers.hasKey("reasonCode")))
				.andExpect(jsonPath("$.data[0].entries[0].reasonCode").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.data[0].entries[0]", Matchers.hasKey("resolvedUrl")))
				.andExpect(jsonPath("$.data[0].entries[0].resolvedUrl").value(Matchers.nullValue()))
				.andExpect(jsonPath("$.data[0].entries[0]", Matchers.hasKey("itemId")))
				.andExpect(jsonPath("$.data[0].entries[0].itemId").value(Matchers.nullValue()));
	}

	@Test
	void itemId가_값이면_문자열로_직렬화된다() throws Exception {
		RegistrationRow row = new RegistrationRow(1L, 7L, OffsetDateTime.parse("2026-07-29T00:00:00Z"),
				OffsetDateTime.parse("2026-07-29T00:05:00Z"),
				List.of(entry(1L, 1, "https://www.instagram.com/p/ABC123/", "post", "success", null, null, null,
						12345L)));
		given(repository.findRecentByUser(eq(7L), eq(50))).willReturn(List.of(row));
		given(repository.countByUser(7L)).willReturn(1L);

		mockMvc.perform(get("/v1/monitoring/registrations").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].entries[0].itemId").value("12345"));
	}

	@Test
	void meta_total은_최근_50건_창과_무관하게_전체_건수다() throws Exception {
		List<RegistrationRow> fiftyRows = IntStream.rangeClosed(1, 50)
				.mapToObj(i -> new RegistrationRow(i, 7L, OffsetDateTime.parse("2026-07-29T00:00:00Z"), null,
						List.of()))
				.toList();
		given(repository.findRecentByUser(eq(7L), eq(50))).willReturn(fiftyRows);
		given(repository.countByUser(7L)).willReturn(60L);

		mockMvc.perform(get("/v1/monitoring/registrations").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(50))
				.andExpect(jsonPath("$.meta.total").value(60));
	}

	@Test
	void 비로그인은_401이다() throws Exception {
		mockMvc.perform(get("/v1/monitoring/registrations"))
				.andExpect(status().isUnauthorized());

		then(repository).shouldHaveNoInteractions();
	}
}
