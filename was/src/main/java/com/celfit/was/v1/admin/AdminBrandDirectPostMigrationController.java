package com.celfit.was.v1.admin;

import com.celfit.was.v1.brandmonitoring.BrandDirectPostMigrationJob;
import com.celfit.was.v1.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * direct 게시물 이관 잡 어드민 트리거(2026-08-18 direct 통합 §M2) — 배포 직후 운영 1회 실행용.
 * 스케줄 없음, 어드민이 수동으로 호출한다(운영 실행 절차는
 * {@link BrandDirectPostMigrationJob} 클래스 javadoc·구현 계획 문서 §M 참고). 재실행은
 * 멱등하고 안전하다 — {@code migrated_at IS NULL} 대상이 0이 될 때까지 반복 호출해도 된다.
 *
 * <p>{@code /v1/admin/**}는 SecurityConfig가 hasRole("ADMIN")으로 이미 잠근다
 * (AdminNoticesController와 동형) — 이 컨트롤러에 별도 인가 애노테이션이 필요 없다.
 */
@RestController
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class AdminBrandDirectPostMigrationController {

	private final BrandDirectPostMigrationJob job;

	public AdminBrandDirectPostMigrationController(BrandDirectPostMigrationJob job) {
		this.job = job;
	}

	@PostMapping("/v1/admin/brand-direct-posts/migrate")
	public ApiResponse<BrandDirectPostMigrationJob.Result> migrate() {
		return ApiResponse.ok(job.run());
	}
}
