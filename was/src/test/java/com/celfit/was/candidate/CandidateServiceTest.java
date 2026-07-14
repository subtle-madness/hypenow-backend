package com.celfit.was.candidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 상태 전이 규칙(자유 전이 + 동일 상태 거부)과 handle 정규화 — §4-2의 Java 소속 로직. */
class CandidateServiceTest {

	final CandidateRepository repository = mock(CandidateRepository.class);
	final CandidateService service = new CandidateService(repository);

	private Candidate candidate(CandidateStatus status) {
		OffsetDateTime now = OffsetDateTime.parse("2026-07-14T00:00:00Z");
		return new Candidate(1L, "glow", status, null, now, now);
	}

	@Test
	void 동일_상태로의_전이는_400() {
		given(repository.findByHandle("glow")).willReturn(Optional.of(candidate(CandidateStatus.REVIEWING)));

		assertThatThrownBy(() -> service.changeStatus("glow", CandidateStatus.REVIEWING))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
	}

	@Test
	void 역방향_전이도_허용된다() {
		given(repository.findByHandle("glow")).willReturn(Optional.of(candidate(CandidateStatus.COLLABORATING)));
		given(repository.updateStatus("glow", CandidateStatus.REVIEWING))
				.willReturn(Optional.of(candidate(CandidateStatus.REVIEWING)));

		Candidate result = service.changeStatus("glow", CandidateStatus.REVIEWING);

		assertThat(result.status()).isEqualTo(CandidateStatus.REVIEWING);
	}

	@Test
	void status_없는_전이_요청은_400() {
		assertThatThrownBy(() -> service.changeStatus("glow", null))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
	}

	@Test
	void 없는_후보의_전이는_404() {
		given(repository.findByHandle("nope")).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.changeStatus("nope", CandidateStatus.COLLABORATING))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
	}

	@Test
	void 저장_시_handle을_정규화한다() {
		given(repository.insert("glow", null)).willReturn(candidate(CandidateStatus.REVIEWING));

		Candidate result = service.create(" @Glow ", null);

		assertThat(result.handle()).isEqualTo("glow");
	}

	@Test
	void 중복_저장은_409() {
		given(repository.insert(any(), any())).willThrow(new DuplicateKeyException("dup"));

		assertThatThrownBy(() -> service.create("glow", null))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
	}

	@Test
	void 빈_handle은_400() {
		for (String bad : new String[] {null, "", "  ", "@", " @ "}) {
			assertThatThrownBy(() -> service.create(bad, null))
					.isInstanceOfSatisfying(ResponseStatusException.class,
							e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
		}
	}
}
