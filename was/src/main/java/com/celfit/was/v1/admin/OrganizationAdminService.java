package com.celfit.was.v1.admin;

import com.celfit.was.entitlement.FeatureKey;
import com.celfit.was.entitlement.OrganizationRepository;
import com.celfit.was.entitlement.OrganizationRepository.OrganizationRow;
import com.celfit.was.entitlement.Plan;
import com.celfit.was.v1.common.V1ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 어드민 조직 관리 쓰기(계획 Task 3, 설계 2026-08-17 §어드민 API) — 검증·enum 파싱을 전담하고 쓰기는
 * {@link OrganizationRepository}에 위임한다. 인가(hasRole ADMIN)는 SecurityConfig가 처리
 * (AdminNoticesController 관례).
 */
@Service
public class OrganizationAdminService {

	private final OrganizationRepository organizationRepository;
	private final AdminUserRepository userRepository;

	public OrganizationAdminService(OrganizationRepository organizationRepository, AdminUserRepository userRepository) {
		this.organizationRepository = organizationRepository;
		this.userRepository = userRepository;
	}

	public long create(OrganizationCreateRequest request) {
		if (request.name() == null || request.name().isBlank()) {
			throw V1ApiException.validation("조직명을 입력해 주세요.");
		}
		Plan plan = parsePlan(request.plan());
		return organizationRepository.create(request.name(), plan.name(), request.contractStart(),
				request.contractEnd());
	}

	public OrganizationRow requireById(long id) {
		return organizationRepository.findById(id).orElseThrow(OrganizationAdminService::notFound);
	}

	/**
	 * PATCH — Map body에서 허용 키만 골라 컬럼 맵으로 옮긴다(V1MeController.patch 관용구: 키 부재=유지,
	 * 값이 있으면 반영). plan은 문자열 검증 후 enum name으로, 계약기간은 명시적 null도 그대로 허용한다
	 * (계약 해지 시 무기한으로 되돌리는 용도).
	 */
	public OrganizationRow patch(long id, Map<String, Object> body) {
		Map<String, Object> columns = new LinkedHashMap<>();
		if (body.containsKey("plan")) {
			columns.put("plan", parsePlan((String) body.get("plan")).name());
		}
		if (body.containsKey("contractStart")) {
			columns.put("contract_start", parseDate(body.get("contractStart")));
		}
		if (body.containsKey("contractEnd")) {
			columns.put("contract_end", parseDate(body.get("contractEnd")));
		}
		return organizationRepository.patch(id, columns).orElseThrow(OrganizationAdminService::notFound);
	}

	/** 대상 유저 부재 404, 이미 (타·같은) 조직 소속이면 409 ALREADY_MEMBER(UNIQUE(user_id) 위반). */
	public void addMember(long orgId, OrganizationMemberAddRequest request) {
		requireById(orgId);
		if (request.userId() == null) {
			throw V1ApiException.validation("userId를 입력해 주세요.");
		}
		String orgRole = parseOrgRole(request.orgRole());
		if (userRepository.findById(request.userId()).isEmpty()) {
			throw V1ApiException.notFound("USER_NOT_FOUND", "유저를 찾을 수 없습니다.");
		}
		try {
			organizationRepository.addMember(orgId, request.userId(), orgRole);
		} catch (DuplicateKeyException e) {
			throw V1ApiException.conflict("ALREADY_MEMBER", "이미 다른 조직에 소속된 유저예요.");
		}
	}

	public void updateMemberRole(long orgId, long userId, OrganizationMemberRoleRequest request) {
		requireById(orgId);
		String orgRole = parseOrgRole(request.orgRole());
		if (!organizationRepository.updateMemberRole(orgId, userId, orgRole)) {
			throw V1ApiException.notFound("소속 멤버를 찾을 수 없습니다.");
		}
	}

	public void removeMember(long orgId, long userId) {
		requireById(orgId);
		if (!organizationRepository.removeMember(orgId, userId)) {
			throw V1ApiException.notFound("소속 멤버를 찾을 수 없습니다.");
		}
	}

	/** featureKey는 FeatureKey enum 검증 필수(계획 Task 3 계약) — 현재 빈 enum이라 모든 키가 400이다. */
	public void upsertOverride(long orgId, String featureKeyRaw, OrganizationOverrideRequest request) {
		requireById(orgId);
		FeatureKey key = parseFeatureKey(featureKeyRaw);
		if (request.enabled() == null) {
			throw V1ApiException.validation("enabled 값을 입력해 주세요.");
		}
		organizationRepository.upsertOverride(orgId, key.name(), request.enabled(), request.value());
	}

	/**
	 * 삭제는 FeatureKey enum 검증 없이 raw 문자열을 그대로 받는다(OrganizationRepository.deleteOverride
	 * 참고) — enum에서 빠진 키의 잔재 행도 정리할 수 있어야 한다.
	 */
	public void deleteOverride(long orgId, String featureKey) {
		requireById(orgId);
		if (!organizationRepository.deleteOverride(orgId, featureKey)) {
			throw V1ApiException.notFound("오버라이드를 찾을 수 없습니다.");
		}
	}

	private static Plan parsePlan(String raw) {
		try {
			return Plan.valueOf(raw);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw V1ApiException.validation("plan 값이 올바르지 않습니다.");
		}
	}

	private static String parseOrgRole(String raw) {
		if (!"MEMBER".equals(raw) && !"ORG_ADMIN".equals(raw)) {
			throw V1ApiException.validation("orgRole 값이 올바르지 않습니다.");
		}
		return raw;
	}

	private static FeatureKey parseFeatureKey(String raw) {
		try {
			return FeatureKey.valueOf(raw);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw V1ApiException.badRequest("UNKNOWN_FEATURE_KEY", "존재하지 않는 featureKey예요.");
		}
	}

	private static LocalDate parseDate(Object value) {
		if (value == null) {
			return null;
		}
		try {
			return LocalDate.parse(value.toString());
		} catch (DateTimeParseException e) {
			throw V1ApiException.validation("날짜 형식이 올바르지 않습니다.");
		}
	}

	private static V1ApiException notFound() {
		return V1ApiException.notFound("조직을 찾을 수 없습니다.");
	}
}
