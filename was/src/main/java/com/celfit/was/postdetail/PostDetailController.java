package com.celfit.was.postdetail;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 게시물 상세 모달 API — 계약 record 3종을 조회해 블록 응답으로 조립한다. */
@RestController
public class PostDetailController {

	private final PostDetailRepository repository;
	private final PostDetailAssembler assembler;

	public PostDetailController(PostDetailRepository repository, PostDetailAssembler assembler) {
		this.repository = repository;
		this.assembler = assembler;
	}

	@GetMapping("/api/posts/{shortCode}")
	public PostDetailResponse postDetail(@PathVariable String shortCode) {
		return repository.findContent(shortCode)
				.map(content -> assembler.toResponse(
						content,
						repository.findAccount(content.accountHandle()).orElse(null),
						repository.findComments(shortCode)))
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.NOT_FOUND, "게시물을 찾을 수 없습니다: " + shortCode));
	}
}
