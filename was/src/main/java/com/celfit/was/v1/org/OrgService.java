package com.celfit.was.v1.org;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.UserRepository;
import com.celfit.was.entitlement.OrganizationRepository;
import com.celfit.was.entitlement.OrganizationRepository.OrganizationRow;
import com.celfit.was.entitlement.OrganizationRepository.SelfMembership;
import com.celfit.was.v1.common.V1ApiException;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 조직 셀프서비스(계획 Task 4, 설계 2026-08-17 §조직 셀프서비스) — 모든 핸들러가 요청자의 소속을
 * 세션이 아니라 매 요청 DB에서 재해석한다({@link #requireMembership}). 남의 조직 접근은 UNIQUE(user_id)
 * 제약 + orgId 일치 검사로 구조적으로 막힌다(어드민 표면과 달리 org_id를 파라미터로 받지 않는다).
 */
@Service
public class OrgService {

	private final OrganizationRepository organizationRepository;
	private final UserRepository userRepository;

	public OrgService(OrganizationRepository organizationRepository, UserRepository userRepository) {
		this.organizationRepository = organizationRepository;
		this.userRepository = userRepository;
	}

	/** 무소속이면 404(설계 §조직 셀프서비스) — 멤버 누구나 조회 가능. */
	public OrgResponse getOrg(long userId) {
		SelfMembership membership = requireMembership(userId);
		OrganizationRow row = organizationRepository.findById(membership.orgId())
				.orElseThrow(() -> new IllegalStateException(
						"조직 멤버십은 있는데 조직 행이 없음 — orgId=" + membership.orgId()));
		return OrgResponse.from(row, membership.orgRole());
	}

	public List<OrgMemberResponse> listMembers(long userId) {
		SelfMembership membership = requireMembership(userId);
		return organizationRepository.findMembers(membership.orgId()).stream().map(OrgMemberResponse::from).toList();
	}

	/**
	 * ORG_ADMIN만 — 기존 가입 계정을 email 정확 일치로 찾아 추가한다. 대상 없으면 404, 이미
	 * (타·같은) 조직 소속이면 UNIQUE(user_id) 위반으로 409(설계 §조직 셀프서비스).
	 */
	public OrgMemberResponse addMember(long userId, OrgMemberAddRequest request) {
		SelfMembership membership = requireOrgAdmin(userId);
		if (request.email() == null || request.email().isBlank()) {
			throw V1ApiException.validation("email을 입력해 주세요.");
		}
		String orgRole = parseOrgRole(request.orgRole());
		AppUser target = userRepository.findByEmail(request.email())
				.orElseThrow(() -> V1ApiException.notFound("USER_NOT_FOUND", "유저를 찾을 수 없습니다."));
		try {
			organizationRepository.addMember(membership.orgId(), target.id(), orgRole);
		} catch (DuplicateKeyException e) {
			throw V1ApiException.conflict("ALREADY_MEMBER", "이미 다른 조직에 소속된 유저예요.");
		}
		return OrgMemberResponse.from(organizationRepository.findMember(membership.orgId(), target.id())
				.orElseThrow());
	}

	/**
	 * ORG_ADMIN만 — 자기 자신의 강등도 허용한다(설계: 조직에 ORG_ADMIN이 0명이 되는 것도 허용,
	 * 운영진이 어드민 API로 복구 가능).
	 */
	public OrgMemberResponse updateMemberRole(long userId, long targetUserId, OrgMemberRoleRequest request) {
		SelfMembership membership = requireOrgAdmin(userId);
		requireSameOrg(membership.orgId(), targetUserId);
		String orgRole = parseOrgRole(request.orgRole());
		organizationRepository.updateMemberRole(membership.orgId(), targetUserId, orgRole);
		return OrgMemberResponse.from(organizationRepository.findMember(membership.orgId(), targetUserId)
				.orElseThrow());
	}

	/** ORG_ADMIN만 — 자기 자신의 탈퇴(마지막 ORG_ADMIN 포함)도 허용한다(설계 동일 근거). */
	public void removeMember(long userId, long targetUserId) {
		SelfMembership membership = requireOrgAdmin(userId);
		requireSameOrg(membership.orgId(), targetUserId);
		organizationRepository.removeMember(membership.orgId(), targetUserId);
	}

	private SelfMembership requireMembership(long userId) {
		return organizationRepository.findMembershipByUserId(userId)
				.orElseThrow(() -> V1ApiException.notFound("조직에 소속되어 있지 않습니다."));
	}

	private SelfMembership requireOrgAdmin(long userId) {
		SelfMembership membership = requireMembership(userId);
		if (!"ORG_ADMIN".equals(membership.orgRole())) {
			throw V1ApiException.forbidden("NOT_ORG_ADMIN", "조직 관리자만 할 수 있는 작업이에요.");
		}
		return membership;
	}

	/** 대상이 요청자와 같은 조직 소속이 아니면 404 — "존재하지만 남의 조직"과 "부재"를 구분하지 않는다(스코프 은닉). */
	private void requireSameOrg(long orgId, long targetUserId) {
		boolean sameOrg = organizationRepository.findMembershipByUserId(targetUserId)
				.filter(membership -> membership.orgId() == orgId)
				.isPresent();
		if (!sameOrg) {
			throw V1ApiException.notFound("소속 멤버를 찾을 수 없습니다.");
		}
	}

	private static String parseOrgRole(String raw) {
		if (!"MEMBER".equals(raw) && !"ORG_ADMIN".equals(raw)) {
			throw V1ApiException.validation("orgRole 값이 올바르지 않습니다.");
		}
		return raw;
	}
}
