package com.celfit.was.v1.brand;

import com.celfit.was.v1.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 브랜드 협업 조회 — 리포트 브랜드 칩 호버 (스펙 6.5 v2). */
@RestController
public class V1BrandController {

	private final V1BrandRepository repository;

	public V1BrandController(V1BrandRepository repository) {
		this.repository = repository;
	}

	@GetMapping("/v1/brands/{brand}/influencers")
	public ApiResponse<List<BrandInfluencer>> influencers(@PathVariable String brand) {
		return ApiResponse.ok(repository.findInfluencers(brand));
	}
}
