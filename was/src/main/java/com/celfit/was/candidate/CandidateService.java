package com.celfit.was.candidate;

import java.util.List;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 후보 상태 변화·트랜잭션의 자리 (ARCHITECTURE §4-2).
 * 전이 규칙: 자유 전이(역방향·스킵 허용) + 동일 상태 전이만 거부 —
 * 마케터가 파이프라인을 되돌리거나 건너뛰는 걸 막지 않는다. 규칙이 생기면 이 클래스만 고친다.
 */
@Service
public class CandidateService {

	private final CandidateRepository repository;

	public CandidateService(CandidateRepository repository) {
		this.repository = repository;
	}

	/** handle 정규화(trim·선행 @ 제거·소문자) 후 저장. 초기 상태는 REVIEWING(DB 기본값). */
	@Transactional
	public Candidate create(String handle, String memo) {
		String normalized = normalize(handle);
		try {
			return repository.insert(normalized, memo);
		} catch (DuplicateKeyException e) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 저장된 후보입니다: " + normalized);
		}
	}

	public List<Candidate> list(CandidateStatus status) {
		return repository.findAll(status);
	}

	public Candidate get(String handle) {
		return repository.findByHandle(normalize(handle))
				.orElseThrow(() -> notFound(handle));
	}

	@Transactional
	public Candidate changeStatus(String handle, CandidateStatus target) {
		if (target == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status는 필수입니다");
		}
		String normalized = normalize(handle);
		Candidate current = repository.findByHandle(normalized)
				.orElseThrow(() -> notFound(handle));
		if (current.status() == target) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"이미 " + target + " 상태입니다: " + normalized);
		}
		return repository.updateStatus(normalized, target)
				.orElseThrow(() -> notFound(handle));
	}

	/** memo null이면 삭제(빈 메모). */
	@Transactional
	public Candidate updateMemo(String handle, String memo) {
		return repository.updateMemo(normalize(handle), memo)
				.orElseThrow(() -> notFound(handle));
	}

	@Transactional
	public void delete(String handle) {
		if (!repository.delete(normalize(handle))) {
			throw notFound(handle);
		}
	}

	static String normalize(String handle) {
		if (handle == null || handle.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "handle은 필수입니다");
		}
		String trimmed = handle.strip();
		if (trimmed.startsWith("@")) {
			trimmed = trimmed.substring(1);
		}
		if (trimmed.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "handle은 필수입니다");
		}
		return trimmed.toLowerCase(Locale.ROOT);
	}

	private ResponseStatusException notFound(String handle) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "후보를 찾을 수 없습니다: " + handle);
	}
}
