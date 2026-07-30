package com.celfit.monitoring.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeywordRuleTest {

	@Test
	void and는_전부_있어야_매칭() {
		var rule = new KeywordRule(List.of("샤넬", "립"), List.of(), List.of());
		assertThat(rule.matches("샤넬 신상 립 발색")).isTrue();
		assertThat(rule.matches("샤넬 신상 파운데이션")).isFalse();
	}

	@Test
	void any는_하나만_있어도_매칭() {
		var rule = new KeywordRule(List.of(), List.of("샤넬", "chanel"), List.of());
		assertThat(rule.matches("New CHANEL lipstick")).isTrue();  // 대소문자 무시
		assertThat(rule.matches("디올 신상")).isFalse();
	}

	@Test
	void exclude가_있으면_배제() {
		var rule = new KeywordRule(List.of(), List.of("샤넬"), List.of("이벤트"));
		assertThat(rule.matches("샤넬 이벤트 공지")).isFalse();
		assertThat(rule.matches("샤넬 신상 리뷰")).isTrue();  // exclude 목록이 있어도 걸리지 않으면 매칭
	}

	@Test
	void and와_any_조합() {
		var rule = new KeywordRule(List.of("샤넬"), List.of("립", "쿠션"), List.of());
		assertThat(rule.matches("샤넬 쿠션 리뷰")).isTrue();
		assertThat(rule.matches("샤넬 향수 리뷰")).isFalse();
	}

	@Test
	void and_any_모두_비면_무효_그리고_무매칭() {
		var rule = new KeywordRule(List.of(), List.of(), List.of("이벤트"));
		assertThat(rule.isValid()).isFalse();
		assertThat(rule.matches("아무 캡션")).isFalse();
	}

	@Test
	void null_캡션은_무매칭() {
		assertThat(new KeywordRule(List.of("샤넬"), List.of(), List.of()).matches(null)).isFalse();
	}

	@Test
	void 빈_문자열_키워드만_있으면_무효() {
		// ""는 contains("")가 항상 true라 전 캡션 매칭 — 생성 시 제거되어 빈 목록 → 무효
		var rule = new KeywordRule(List.of(), List.of(""), List.of());
		assertThat(rule.any()).isEmpty();
		assertThat(rule.isValid()).isFalse();
		assertThat(rule.matches("아무 캡션")).isFalse();
	}

	@Test
	void 공백_null_원소는_제거되고_나머지로_매칭() {
		var rule = new KeywordRule(List.of(), Arrays.asList("샤넬", "", "   ", null), List.of());
		assertThat(rule.any()).containsExactly("샤넬");
		assertThat(rule.isValid()).isTrue();
		assertThat(rule.matches("샤넬 신상")).isTrue();
		assertThat(rule.matches("디올 신상")).isFalse();
	}

	// ── matchedTerms(v1.1) — 계약 §3 detected_candidate.matched_keywords의 정본 로직 ──────────

	@Test
	void matchedTerms는_and_전부와_any_중_실제_존재한_것만_등록_순서로_돌려준다() {
		var rule = new KeywordRule(List.of("샤넬"), List.of("립", "쿠션", "향수"), List.of());
		// 캡션에 "쿠션"만 있고 "립"·"향수"는 없다 — any 중 실제 존재한 것만 담긴다.
		assertThat(rule.matchedTerms("샤넬 쿠션 리뷰")).containsExactly("샤넬", "쿠션");
	}

	@Test
	void matchedTerms는_대소문자를_무시하고_등록_원문_그대로_돌려준다() {
		var rule = new KeywordRule(List.of(), List.of("CHANEL"), List.of());
		// 캡션은 소문자지만 반환값은 등록 원문(대문자)이어야 한다 — was가 그대로 노출한다(계약 §3).
		assertThat(rule.matchedTerms("new chanel lipstick")).containsExactly("CHANEL");
	}

	@Test
	void 매칭이_성립하지_않으면_matchedTerms는_빈_리스트다() {
		var rule = new KeywordRule(List.of("샤넬"), List.of("립"), List.of());
		assertThat(rule.matchedTerms("디올 립스틱")).isEmpty();   // and(샤넬) 불일치
		assertThat(rule.matchedTerms(null)).isEmpty();
	}

	@Test
	void exclude에_걸리면_matchedTerms도_빈_리스트다() {
		var rule = new KeywordRule(List.of(), List.of("샤넬"), List.of("이벤트"));
		assertThat(rule.matchedTerms("샤넬 이벤트 공지")).isEmpty();
	}

	/** matches()는 matchedTerms 기반으로 재구성됐다 — 판정이 갈리지 않는지 회귀로 못박는다. */
	@Test
	void matches는_matchedTerms가_비었는지로_판정한다() {
		var rule = new KeywordRule(List.of("샤넬"), List.of("립", "쿠션"), List.of());
		assertThat(rule.matches("샤넬 쿠션 리뷰")).isTrue();
		assertThat(rule.matchedTerms("샤넬 쿠션 리뷰")).isNotEmpty();
		assertThat(rule.matches("샤넬 향수 리뷰")).isFalse();
		assertThat(rule.matchedTerms("샤넬 향수 리뷰")).isEmpty();
	}
}
