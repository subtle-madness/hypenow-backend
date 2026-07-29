package com.celfit.monitoring.domain;

import static org.assertj.core.api.Assertions.assertThat;

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
}
