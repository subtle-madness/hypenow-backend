package com.celfit.was.v1.stats;

import com.celfit.contract.analysis.LandingStats;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.V1ApiException;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 6.20 랜딩 통계 — 인증 Public. 주간 배치 갱신이라 강한 HTTP 캐시를 허용한다(스펙 6.20). */
@RestController
public class V1StatsController {

	private final V1StatsRepository repository;

	public V1StatsController(V1StatsRepository repository) {
		this.repository = repository;
	}

	@GetMapping("/v1/stats")
	public ResponseEntity<ApiResponse<StatsResponse>> stats() {
		LandingStats s = repository.find()
				.orElseThrow(() -> V1ApiException.notFound("통계를 찾을 수 없습니다."));
		return ResponseEntity.ok()
				.cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
				.body(ApiResponse.ok(StatsResponse.from(s)));
	}
}
