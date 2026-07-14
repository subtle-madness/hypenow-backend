package com.celfit.was.candidate;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 후보 저장소 — was가 소유한 서비스 데이터(app 스키마)라 읽기·쓰기 모두 여기서 한다.
 * SQL은 app.candidates만 만진다 — 분석 결과 테이블과의 조인 금지 (ARCHITECTURE §4-4).
 */
@Repository
public class CandidateRepository {

	private static final String COLUMNS = "id, handle, status, memo, created_at, updated_at";

	private final JdbcClient jdbcClient;

	public CandidateRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public Candidate insert(String handle, String memo) {
		return jdbcClient.sql("""
				INSERT INTO app.candidates (handle, memo)
				VALUES (:handle, :memo)
				RETURNING %s
				""".formatted(COLUMNS))
				.param("handle", handle)
				.param("memo", memo)
				.query(Candidate.class)
				.single();
	}

	public Optional<Candidate> findByHandle(String handle) {
		return jdbcClient.sql("""
				SELECT %s FROM app.candidates
				WHERE handle = :handle
				""".formatted(COLUMNS))
				.param("handle", handle)
				.query(Candidate.class)
				.optional();
	}

	/** status가 null이면 전체. 최근 손댄 후보가 앞에 오도록 updated_at 내림차순. */
	public List<Candidate> findAll(CandidateStatus status) {
		if (status == null) {
			return jdbcClient.sql("""
					SELECT %s FROM app.candidates
					ORDER BY updated_at DESC, id DESC
					""".formatted(COLUMNS))
					.query(Candidate.class)
					.list();
		}
		return jdbcClient.sql("""
				SELECT %s FROM app.candidates
				WHERE status = :status
				ORDER BY updated_at DESC, id DESC
				""".formatted(COLUMNS))
				.param("status", status.name())
				.query(Candidate.class)
				.list();
	}

	public Optional<Candidate> updateStatus(String handle, CandidateStatus status) {
		return jdbcClient.sql("""
				UPDATE app.candidates
				SET status = :status, updated_at = now()
				WHERE handle = :handle
				RETURNING %s
				""".formatted(COLUMNS))
				.param("handle", handle)
				.param("status", status.name())
				.query(Candidate.class)
				.optional();
	}

	public Optional<Candidate> updateMemo(String handle, String memo) {
		return jdbcClient.sql("""
				UPDATE app.candidates
				SET memo = :memo, updated_at = now()
				WHERE handle = :handle
				RETURNING %s
				""".formatted(COLUMNS))
				.param("handle", handle)
				.param("memo", memo)
				.query(Candidate.class)
				.optional();
	}

	public boolean delete(String handle) {
		return jdbcClient.sql("DELETE FROM app.candidates WHERE handle = :handle")
				.param("handle", handle)
				.update() > 0;
	}
}
