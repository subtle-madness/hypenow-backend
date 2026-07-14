package com.celfit.was.candidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.IntegrationTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * app.candidates CRUD 왕복 — was가 Flyway를 소유하므로 손 DDL 없이
 * 실제 V1 마이그레이션이 만든 스키마(CHECK·UNIQUE·기본값 포함)를 그대로 검증한다.
 */
class CandidateRepositoryTest extends IntegrationTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	CandidateRepository repository;

	@BeforeEach
	void cleanTable() {
		jdbcTemplate.execute("DELETE FROM app.candidates");
	}

	@Test
	void 저장하면_기본_상태_REVIEWING과_타임스탬프가_채워진다() {
		Candidate saved = repository.insert("glow", "건성 캠페인 후보");

		assertThat(saved.id()).isPositive();
		assertThat(saved.handle()).isEqualTo("glow");
		assertThat(saved.status()).isEqualTo(CandidateStatus.REVIEWING);
		assertThat(saved.memo()).isEqualTo("건성 캠페인 후보");
		assertThat(saved.createdAt()).isNotNull();
		assertThat(saved.updatedAt()).isNotNull();
	}

	@Test
	void 중복_handle은_DuplicateKeyException() {
		repository.insert("glow", null);

		assertThatThrownBy(() -> repository.insert("glow", null))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void 허용되지_않은_status는_CHECK_제약으로_거부된다() {
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO app.candidates (handle, status) VALUES ('bad', 'DONE')"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 목록은_updated_at_내림차순이고_status_필터가_적용된다() {
		repository.insert("first", null);
		repository.insert("second", null);
		// now() 동률로 순서가 흔들리지 않게 갱신 시각을 명시적으로 벌린다
		jdbcTemplate.update("""
				UPDATE app.candidates SET status = 'COLLABORATING', updated_at = now() + interval '1 hour'
				WHERE handle = 'first'
				""");

		List<Candidate> all = repository.findAll(null);
		assertThat(all).extracting(Candidate::handle).containsExactly("first", "second");

		List<Candidate> collaborating = repository.findAll(CandidateStatus.COLLABORATING);
		assertThat(collaborating).extracting(Candidate::handle).containsExactly("first");
	}

	@Test
	void 상태와_메모_갱신은_updated_at을_올리고_행을_돌려준다() {
		Candidate saved = repository.insert("glow", null);

		Optional<Candidate> statusUpdated = repository.updateStatus("glow", CandidateStatus.CONTACT_PLANNED);
		assertThat(statusUpdated).hasValueSatisfying(c -> {
			assertThat(c.status()).isEqualTo(CandidateStatus.CONTACT_PLANNED);
			assertThat(c.updatedAt()).isAfterOrEqualTo(saved.updatedAt());
		});

		Optional<Candidate> memoUpdated = repository.updateMemo("glow", "7월 컨택");
		assertThat(memoUpdated).hasValueSatisfying(c -> assertThat(c.memo()).isEqualTo("7월 컨택"));

		Optional<Candidate> memoCleared = repository.updateMemo("glow", null);
		assertThat(memoCleared).hasValueSatisfying(c -> assertThat(c.memo()).isNull());
	}

	@Test
	void 없는_handle_갱신과_삭제는_빈_결과다() {
		assertThat(repository.updateStatus("nope", CandidateStatus.COLLABORATING)).isEmpty();
		assertThat(repository.updateMemo("nope", "x")).isEmpty();
		assertThat(repository.delete("nope")).isFalse();

		repository.insert("glow", null);
		assertThat(repository.delete("glow")).isTrue();
		assertThat(repository.findByHandle("glow")).isEmpty();
	}
}
