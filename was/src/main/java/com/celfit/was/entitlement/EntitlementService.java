package com.celfit.was.entitlement;

import com.celfit.was.entitlement.EntitlementRepository.FeatureOverride;
import com.celfit.was.entitlement.EntitlementRepository.Membership;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * entitlement 판정 단일 지점(설계 2026-08-17 §판정 로직). 판정은 세션이 아닌 매번 DB 기준으로 다시 계산한다
 * (캐시 없음 — 어드민 role 신선도 재확인 선례와 동일, 성능 문제가 실측되면 후속 과제).
 */
@Service
public class EntitlementService {

	private static final Logger log = LoggerFactory.getLogger(EntitlementService.class);

	private final EntitlementRepository repository;

	public EntitlementService(EntitlementRepository repository) {
		this.repository = repository;
	}

	/**
	 * 판정 규칙(설계 §판정 로직 1~3):
	 * 1. 멤버십 없으면 → FREE 폴백.
	 * 2. contract_end가 NOT NULL이고 오늘보다 과거면 → FREE 폴백(만료 배치 없이 판정 시점 계산).
	 * 3. 그 외 → plan 기본값 위에 오버라이드 합성.
	 */
	public Entitlements entitlementsFor(long userId) {
		return repository.findMembership(userId)
				.filter(membership -> !isExpired(membership))
				.map(membership -> compose(Plan.valueOf(membership.plan()), repository.findOverrides(membership.orgId())))
				.orElseGet(() -> compose(Plan.FREE, List.of()));
	}

	private static boolean isExpired(Membership membership) {
		LocalDate contractEnd = membership.contractEnd();
		return contractEnd != null && contractEnd.isBefore(LocalDate.now());
	}

	/**
	 * plan 기본값 위에 오버라이드를 합성하는 순수 함수 — DB 조회가 없어 단위 테스트로 직접 검증한다
	 * (계획 Task 1: FeatureKey가 아직 빈 enum이라 실 오버라이드 행을 만드는 IT는 불가능, EntitlementServiceComposeTest
	 * 참고). enabled=true면 키 추가, false면 제거. value가 NOT NULL이면 기본 파라미터를 덮어쓴다.
	 * FeatureKey enum에 없는 feature_key는 무시하고 warn 로그(키 삭제 후 잔재 행 대비).
	 */
	static Entitlements compose(Plan plan, List<FeatureOverride> overrides) {
		Set<FeatureKey> enabled = EnumSet.noneOf(FeatureKey.class);
		enabled.addAll(PlanDefaults.enabledFor(plan));
		Map<FeatureKey, JsonNode> params = new EnumMap<>(FeatureKey.class);
		params.putAll(PlanDefaults.paramsFor(plan));

		for (FeatureOverride override : overrides) {
			FeatureKey key;
			try {
				key = FeatureKey.valueOf(override.featureKey());
			} catch (IllegalArgumentException e) {
				log.warn("FeatureKey enum에 없는 오버라이드 키 무시 — featureKey={}", override.featureKey());
				continue;
			}
			if (override.enabled()) {
				enabled.add(key);
			} else {
				enabled.remove(key);
			}
			if (override.value() != null) {
				params.put(key, override.value());
			}
		}
		return new Entitlements(plan, enabled, params);
	}
}
