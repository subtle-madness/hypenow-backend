package com.celfit.was.contentlist;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 랭킹 목록 API — 프론트 URL 파라미터 계약(§7 2026-07-14)을 ContentListQuery로 매핑해 리포지토리·어셈블러로 위임한다. */
@RestController
public class ContentListController {

	private final ContentListRepository repository;
	private final ContentListAssembler assembler;

	public ContentListController(ContentListRepository repository, ContentListAssembler assembler) {
		this.repository = repository;
		this.assembler = assembler;
	}

	@GetMapping("/api/contents")
	public ContentListResponse contents(
			@RequestParam(name = "start_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
			@RequestParam(name = "end_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
			@RequestParam(name = "main_category", required = false) String mainCategory,
			@RequestParam(name = "mid_category", required = false) String midCategory,
			@RequestParam(name = "sub_category", required = false) String subCategory,
			@RequestParam(name = "content_type", required = false) String contentType,
			@RequestParam(required = false) String follower,
			@RequestParam(name = "ad_type", required = false) String adType,
			@RequestParam(required = false) String distributor,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) String sort) {
		ContentListQuery query = ContentListQuery.of(startDate, endDate, mainCategory, midCategory,
				subCategory, contentType, follower, adType, distributor, q, sort);
		return assembler.toResponse(
				repository.countContents(query), repository.findContents(query), query.cutoff());
	}
}
