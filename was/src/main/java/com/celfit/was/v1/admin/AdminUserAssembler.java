package com.celfit.was.v1.admin;

import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.v1.common.FeatureOverridesCodec;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.saved.V1SavedRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * AdminUserRow → AdminUserSummary 조합기(설계 2026-08-01 §4) — campaignCount·monitoringCount·
 * savedCount는 페이지 내 유저 id 묶음 IN절 배치 집계(전 유저 스캔 금지), health는
 * {@link AdminMonitoringHealthService}가 users·registrations 공용으로 유도한 값을 재사용한다.
 */
@Component
public class AdminUserAssembler {

	private final CampaignRepository campaignRepository;
	private final MonitoringItemRepository itemRepository;
	private final V1SavedRepository savedRepository;
	private final AdminMonitoringHealthService healthService;
	private final FeatureOverridesCodec featureOverridesCodec;

	public AdminUserAssembler(CampaignRepository campaignRepository, MonitoringItemRepository itemRepository,
			V1SavedRepository savedRepository, AdminMonitoringHealthService healthService,
			FeatureOverridesCodec featureOverridesCodec) {
		this.campaignRepository = campaignRepository;
		this.itemRepository = itemRepository;
		this.savedRepository = savedRepository;
		this.healthService = healthService;
		this.featureOverridesCodec = featureOverridesCodec;
	}

	public List<AdminUserSummary> assemble(List<AdminUserRow> rows) {
		if (rows.isEmpty()) {
			return List.of();
		}
		List<Long> ids = rows.stream().map(AdminUserRow::id).toList();
		Map<Long, Long> campaignCounts = campaignRepository.countByUsers(ids);
		Map<Long, Long> monitoringCounts = itemRepository.countByUsers(ids);
		Map<Long, Long> savedCounts = savedRepository.countSavedByUsers(ids);
		// 전체 유저 대상 계산(§4 "health와 rows가 같은 모집단") — 페이지 크기가 수십 건대라 매번
		// 다시 계산해도 비용이 낮다(A7). 다른 스코프로 좁히면 registrations 엔드포인트와 판정이 갈릴 수 있다.
		Map<Long, String> health = healthService.healthByUser(healthService.computeActiveRows());

		return rows.stream()
				.map(row -> new AdminUserSummary(String.valueOf(row.id()), row.email(),
						row.name() == null ? "" : row.name(), blankToEmpty(row.companyName()), row.userType(),
						row.signupRoute(), KstTimestamps.toKstIso(row.createdAt()),
						KstTimestamps.toKstIso(row.lastActiveAt()), campaignCounts.getOrDefault(row.id(), 0L),
						monitoringCounts.getOrDefault(row.id(), 0L), savedCounts.getOrDefault(row.id(), 0L),
						health.getOrDefault(row.id(), "ok"),
						featureOverridesCodec.read(row.featureOverrides())))
				.toList();
	}

	/** companyName·jobTitle 계약 — null이면 ""(상세 AdminUsersController.blankToEmpty와 동일 규칙). */
	private static String blankToEmpty(String value) {
		return value == null ? "" : value;
	}
}
