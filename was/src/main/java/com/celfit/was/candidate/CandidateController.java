package com.celfit.was.candidate;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 후보 관리 API — 서비스 데이터(app 스키마)의 첫 소비자. 계약은 plans/2026-07-14-task-g-service-data.md §4. */
@RestController
public class CandidateController {

	private final CandidateService service;

	public CandidateController(CandidateService service) {
		this.service = service;
	}

	public record CreateRequest(String handle, String memo) {}

	public record StatusRequest(CandidateStatus status) {}

	public record MemoRequest(String memo) {}

	@PostMapping("/api/candidates")
	@ResponseStatus(HttpStatus.CREATED)
	public CandidateResponse create(@RequestBody CreateRequest request) {
		return CandidateResponse.from(service.create(request.handle(), request.memo()));
	}

	@GetMapping("/api/candidates")
	public CandidateResponse.ListResponse list(@RequestParam(required = false) CandidateStatus status) {
		return CandidateResponse.ListResponse.from(service.list(status));
	}

	@GetMapping("/api/candidates/{handle}")
	public CandidateResponse get(@PathVariable String handle) {
		return CandidateResponse.from(service.get(handle));
	}

	@PutMapping("/api/candidates/{handle}/status")
	public CandidateResponse changeStatus(@PathVariable String handle, @RequestBody StatusRequest request) {
		return CandidateResponse.from(service.changeStatus(handle, request.status()));
	}

	@PutMapping("/api/candidates/{handle}/memo")
	public CandidateResponse updateMemo(@PathVariable String handle, @RequestBody MemoRequest request) {
		return CandidateResponse.from(service.updateMemo(handle, request.memo()));
	}

	@DeleteMapping("/api/candidates/{handle}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String handle) {
		service.delete(handle);
	}
}
