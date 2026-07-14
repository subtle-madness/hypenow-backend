package com.celfit.was.candidate;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(controllers = CandidateController.class)
class CandidateControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	CandidateService service;

	private Candidate glow(CandidateStatus status) {
		return new Candidate(1L, "glow", status, "건성 캠페인 후보",
				OffsetDateTime.parse("2026-07-14T00:00:00Z"),
				OffsetDateTime.parse("2026-07-14T00:00:00Z"));
	}

	@Test
	void 후보_저장은_201과_후보_JSON을_돌려준다() throws Exception {
		given(service.create("@glow", "건성 캠페인 후보")).willReturn(glow(CandidateStatus.REVIEWING));

		mockMvc.perform(post("/api/candidates")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"handle": "@glow", "memo": "건성 캠페인 후보"}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.handle").value("glow"))
				.andExpect(jsonPath("$.status").value("REVIEWING"))
				.andExpect(jsonPath("$.memo").value("건성 캠페인 후보"));
	}

	@Test
	void 목록은_items로_감싸_내려준다() throws Exception {
		given(service.list(isNull())).willReturn(List.of(glow(CandidateStatus.REVIEWING)));

		mockMvc.perform(get("/api/candidates"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].handle").value("glow"));
	}

	@Test
	void 목록_status_필터가_서비스로_전달된다() throws Exception {
		given(service.list(CandidateStatus.COLLABORATING)).willReturn(List.of(glow(CandidateStatus.COLLABORATING)));

		mockMvc.perform(get("/api/candidates").param("status", "COLLABORATING"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].status").value("COLLABORATING"));
	}

	@Test
	void 잘못된_status_필터는_400() throws Exception {
		mockMvc.perform(get("/api/candidates").param("status", "DONE"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 상태_전이는_갱신된_후보를_돌려준다() throws Exception {
		given(service.changeStatus("glow", CandidateStatus.CONTACT_PLANNED))
				.willReturn(glow(CandidateStatus.CONTACT_PLANNED));

		mockMvc.perform(put("/api/candidates/glow/status")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"status": "CONTACT_PLANNED"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONTACT_PLANNED"));
	}

	@Test
	void 잘못된_status_값의_전이는_400() throws Exception {
		mockMvc.perform(put("/api/candidates/glow/status")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"status": "DONE"}
								"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 없는_후보는_404가_전파된다() throws Exception {
		given(service.get("nope")).willThrow(
				new ResponseStatusException(HttpStatus.NOT_FOUND, "후보를 찾을 수 없습니다: nope"));

		mockMvc.perform(get("/api/candidates/nope"))
				.andExpect(status().isNotFound());
	}

	@Test
	void 메모_수정은_갱신된_후보를_돌려준다() throws Exception {
		given(service.updateMemo("glow", "7월 컨택")).willReturn(glow(CandidateStatus.REVIEWING));

		mockMvc.perform(put("/api/candidates/glow/memo")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"memo": "7월 컨택"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.handle").value("glow"));
	}

	@Test
	void 삭제는_204() throws Exception {
		mockMvc.perform(delete("/api/candidates/glow"))
				.andExpect(status().isNoContent());
	}
}
