package com.celfit.was.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.entitlement.EntitlementRepository.FeatureOverride;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link EntitlementService#compose} 순수 함수 단위 테스트 — DB 없이 가짜 오버라이드 행(record)으로 검증한다
 * (계획 Task 1). plan 기본값(PlanDefaults)과 FeatureKey enum이 모두 제품 키 0개인 채로 시작하므로
 * (설계 2026-08-17 "1차 범위는 인프라까지"), 지금 유효한 FeatureKey 상수가 하나도 없다 — 즉
 * {@code FeatureKey.valueOf(...)}는 어떤 문자열을 넣어도 항상 실패한다.
 *
 * <p><b>알려진 한계:</b> 그래서 이 클래스는 "enum에 없는 키 무시" 분기만 실제로 검증할 수 있다(지금은
 * 모든 문자열이 그 분기에 해당한다). "on 추가"·"off 제거"·"value 덮어쓰기" 분기는 compose()의 코드 리뷰로
 * 로직 정합성만 확인했고, FeatureKey에 실 상수가 배선된 뒤 그 값으로 만든 오버라이드 행으로 후속
 * 테스트를 추가해야 실제로 커버된다.
 */
class EntitlementServiceComposeTest {

	@Test
	void 오버라이드가_없으면_plan_기본값을_그대로_반환한다() {
		Entitlements result = EntitlementService.compose(Plan.FREE, List.of());

		assertThat(result.plan()).isEqualTo(Plan.FREE);
		assertThat(result.enabled()).isEqualTo(PlanDefaults.enabledFor(Plan.FREE));
		assertThat(result.params()).isEqualTo(PlanDefaults.paramsFor(Plan.FREE));
	}

	// FeatureKey가 빈 enum이라 지금은 어떤 featureKey 문자열이든 이 분기를 탄다(클래스 javadoc 참고).
	@Test
	void enum에_없는_feature_key는_무시하고_예외를_던지지_않는다() {
		List<FeatureOverride> overrides = List.of(
				new FeatureOverride("NOT_A_REAL_KEY", true, null),
				new FeatureOverride("ANOTHER_UNKNOWN_KEY", false, null));

		Entitlements result = EntitlementService.compose(Plan.ENTERPRISE, overrides);

		assertThat(result.plan()).isEqualTo(Plan.ENTERPRISE);
		assertThat(result.enabled()).isEqualTo(PlanDefaults.enabledFor(Plan.ENTERPRISE));
		assertThat(result.params()).isEqualTo(PlanDefaults.paramsFor(Plan.ENTERPRISE));
	}
}
