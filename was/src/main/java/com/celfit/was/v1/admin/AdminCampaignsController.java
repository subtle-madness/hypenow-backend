package com.celfit.was.v1.admin;

import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.common.KstTimestamps;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /v1/admin/campaigns(프론트 변경요청서 4-2-6절, 08-02) — 파라미터 없이 전체 캠페인을
 * createdAt 내림차순으로 반환한다. registrationCount·userName은 AdminMonitoringRegistrationsController와
 * 동형으로 캠페인 id·유저 id 묶음을 배치 조회해 Java에서 결합한다(N+1 금지). status는 SQL이 아니라
 * AdminCampaignStatus(순수 함수)로 KST 오늘 기준 유도한다.
 */
@RestController
public class AdminCampaignsController {

	private static final String UNKNOWN_USER_LABEL = "(알 수 없음)";

	private final CampaignRepository campaignRepository;
	private final MonitoringItemRepository itemRepository;
	private final AdminUserRepository userRepository;

	public AdminCampaignsController(CampaignRepository campaignRepository, MonitoringItemRepository itemRepository,
			AdminUserRepository userRepository) {
		this.campaignRepository = campaignRepository;
		this.itemRepository = itemRepository;
		this.userRepository = userRepository;
	}

	@GetMapping("/v1/admin/campaigns")
	public ApiResponse<List<AdminCampaignSummary>> list() {
		List<CampaignRow> campaigns = campaignRepository.findAllOrderByCreatedAtDesc();

		Set<Long> campaignIds = campaigns.stream().map(CampaignRow::id).collect(Collectors.toSet());
		Map<Long, Long> registrationCounts = itemRepository.countByCampaigns(campaignIds);
		Set<Long> userIds = campaigns.stream().map(CampaignRow::userId).collect(Collectors.toSet());
		Map<Long, AdminUserRow> usersById =
				userRepository.findByIds(userIds).stream().collect(Collectors.toMap(AdminUserRow::id, u -> u));

		LocalDate today = LocalDate.now(KstTimestamps.KST);
		List<AdminCampaignSummary> rows =
				campaigns.stream().map(campaign -> toSummary(campaign, today, registrationCounts, usersById)).toList();

		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("total", rows.size());
		return ApiResponse.ok(rows, meta);
	}

	private static AdminCampaignSummary toSummary(CampaignRow campaign, LocalDate today,
			Map<Long, Long> registrationCounts, Map<Long, AdminUserRow> usersById) {
		AdminUserRow user = usersById.get(campaign.userId());
		String userName = user == null ? UNKNOWN_USER_LABEL
				: (user.name() == null || user.name().isBlank() ? user.email() : user.name());
		String status = AdminCampaignStatus.deriveStatus(campaign.startDate(), campaign.endDate(), today);
		return new AdminCampaignSummary(String.valueOf(campaign.id()), campaign.name(),
				String.valueOf(campaign.userId()), userName, KstTimestamps.toKstIso(campaign.createdAt()), status,
				registrationCounts.getOrDefault(campaign.id(), 0L), campaign.seedingCount());
	}
}
