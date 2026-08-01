package com.celfit.was.v1.admin;

import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.common.V1ApiException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /v1/admin/monitoring/registrations(설계 2026-08-01 §4) — AdminMonitoringHealthService가
 * users 목록의 health와 공유하는 같은 활성 행 모집단을 status·userId로 좁혀 보여준다.
 */
@RestController
public class AdminMonitoringRegistrationsController {

	private static final String NO_CAMPAIGN_LABEL = "(캠페인 미지정)";
	private static final String UNKNOWN_USER_LABEL = "(알 수 없음)";

	private final AdminMonitoringHealthService healthService;
	private final AdminUserRepository userRepository;
	private final CampaignRepository campaignRepository;

	public AdminMonitoringRegistrationsController(AdminMonitoringHealthService healthService,
			AdminUserRepository userRepository, CampaignRepository campaignRepository) {
		this.healthService = healthService;
		this.userRepository = userRepository;
		this.campaignRepository = campaignRepository;
	}

	@GetMapping("/v1/admin/monitoring/registrations")
	public ApiResponse<AdminMonitoringRegistrationsResponse> registrations(
			@RequestParam(required = false) String status, @RequestParam(required = false) Long userId) {
		if (status != null && !AdminMonitoringStatus.STATUS_ORDER.contains(status)) {
			throw V1ApiException.validation("알 수 없는 status 값이에요: " + status);
		}

		List<AdminMonitoringRow> all = healthService.computeActiveRows();
		List<AdminMonitoringRow> scoped =
				userId == null ? all : all.stream().filter(r -> r.userId() == userId).toList();
		// counts는 status 필터 반영 전(userId 필터만 반영한 scoped) 기준 — 계약 §4.
		Map<String, Long> counts = healthService.counts(scoped);

		List<AdminMonitoringRow> filtered =
				status == null ? scoped : scoped.stream().filter(r -> status.equals(r.status())).toList();
		List<AdminMonitoringRow> sorted =
				filtered.stream().sorted(AdminMonitoringStatus.ROW_COMPARATOR).toList();

		Set<Long> userIds = sorted.stream().map(AdminMonitoringRow::userId).collect(Collectors.toSet());
		Map<Long, AdminUserRow> usersById =
				userRepository.findByIds(userIds).stream().collect(Collectors.toMap(AdminUserRow::id, u -> u));
		Set<Long> campaignIds =
				sorted.stream().map(AdminMonitoringRow::campaignId).filter(Objects::nonNull).collect(Collectors.toSet());
		Map<Long, String> campaignNamesById = campaignRepository.findByIds(campaignIds).stream()
				.collect(Collectors.toMap(CampaignRow::id, CampaignRow::name));

		List<AdminMonitoringRegistrationRow> rows =
				sorted.stream().map(row -> toResponse(row, usersById, campaignNamesById)).toList();
		return ApiResponse.ok(new AdminMonitoringRegistrationsResponse(rows, counts));
	}

	private static AdminMonitoringRegistrationRow toResponse(AdminMonitoringRow row, Map<Long, AdminUserRow> usersById,
			Map<Long, String> campaignNamesById) {
		AdminUserRow user = usersById.get(row.userId());
		String userName = user == null ? UNKNOWN_USER_LABEL
				: (user.name() == null || user.name().isBlank() ? user.email() : user.name());
		String campaignName = row.campaignId() == null ? NO_CAMPAIGN_LABEL
				: campaignNamesById.getOrDefault(row.campaignId(), NO_CAMPAIGN_LABEL);
		return new AdminMonitoringRegistrationRow(String.valueOf(row.itemId()), String.valueOf(row.userId()),
				userName, campaignName, row.handle(), row.contentType(), row.status(),
				KstTimestamps.toKstIso(row.lastCollectedAt()), row.postUrl());
	}
}
