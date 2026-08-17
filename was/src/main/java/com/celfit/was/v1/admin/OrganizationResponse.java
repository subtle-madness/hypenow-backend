package com.celfit.was.v1.admin;

import com.celfit.was.entitlement.OrganizationRepository.OrganizationRow;
import com.celfit.was.v1.common.KstTimestamps;
import java.time.LocalDate;

/**
 * 조직 요약(POST/GET 목록/PATCH 응답 공용) — 어드민 표면 관례를 따라 id는 문자열, plan은 소문자
 * (MeEntitlementsResponse와 동일 관례), created_at은 KST ISO(KstTimestamps).
 */
public record OrganizationResponse(String id, String name, String plan, LocalDate contractStart,
		LocalDate contractEnd, String createdAt) {

	public static OrganizationResponse from(OrganizationRow row) {
		return new OrganizationResponse(String.valueOf(row.id()), row.name(), row.plan().toLowerCase(),
				row.contractStart(), row.contractEnd(), KstTimestamps.toKstIso(row.createdAt()));
	}
}
