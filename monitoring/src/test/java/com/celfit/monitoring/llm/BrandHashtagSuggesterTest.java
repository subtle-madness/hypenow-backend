package com.celfit.monitoring.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * (IG 표시명, 계정명) → 해시태그 1개(2026-09-03 자동 시드 재설계 §3-3) — AdDisclosureExtractorGeminiTest와
 * 같은 fake GeminiHttp 관용구. 출력은 <b>버리지 않고 정리</b>한다(허용 외 문자 제거·30자 절단).
 * stoplist·순수 숫자만 빈 값으로 접히고, 전송·파싱 실패는 예외로 나간다(상위가 FALLBACK으로 내린다).
 */
class BrandHashtagSuggesterTest {

	private static String geminiBody(String innerJson) {
		String escaped = innerJson.replace("\\", "\\\\").replace("\"", "\\\"");
		return """
				{"candidates":[{"content":{"parts":[{"text":"%s"}]}}]}""".formatted(escaped);
	}

	private static BrandHashtagSuggester suggester(GeminiHttp http) {
		return new BrandHashtagSuggester(http, true, "model-x");
	}

	@Test
	void 정상_응답의_해시태그를_돌려준다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"닥터피엘\"}"));

		assertThat(s.suggest("닥터피엘 Dr.PIEL", "dr.piel_official", Set.of())).contains("닥터피엘");
	}

	@Test
	void 선행_샵과_공백을_제거하고_소문자로_정규화한다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"  #CClime  \"}"));

		assertThat(s.suggest("씨씨라임", "cclime_official", Set.of())).contains("cclime");
	}

	/** 허용 외 문자는 제거한다(버리지 않는다) — "닥터 피엘!" → "닥터피엘". */
	@Test
	void 허용_외_문자는_제거하고_남은_값을_쓴다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"닥터 피엘!\"}"));

		assertThat(s.suggest("닥터피엘", "dr.piel_official", Set.of())).contains("닥터피엘");
	}

	@Test
	void 점과_언더스코어_중_언더스코어만_남는다() {
		// 점은 허용 문자가 아니라 제거되고, 언더스코어는 유효 태그 문자라 남는다.
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"dr.piel_official\"}"));

		assertThat(s.suggest("", "dr.piel_official", Set.of())).contains("drpiel_official");
	}

	@Test
	void 삼십자를_넘으면_절단한다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"" + "a".repeat(40) + "\"}"));

		assertThat(s.suggest("브랜드", "brand", Set.of())).contains("a".repeat(30));
	}

	@Test
	void 정리_결과가_비면_빈_값이다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"!!! ???\"}"));

		assertThat(s.suggest("브랜드", "brand", Set.of())).isEmpty();
	}

	@Test
	void 순수_숫자_결과는_빈_값이다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"2026\"}"));

		assertThat(s.suggest("브랜드", "brand", Set.of())).isEmpty();
	}

	@Test
	void stoplist_결과는_빈_값이다() {
		var s = suggester((path, body) -> geminiBody("{\"hashtag\": \"AD\"}"));

		assertThat(s.suggest("브랜드", "brand", Set.of("ad"))).isEmpty();
	}

	@Test
	void 요청에_모델_표시명_계정명이_실린다() {
		AtomicReference<String> sent = new AtomicReference<>();
		var s = new BrandHashtagSuggester((path, body) -> {
			sent.set(path + "\n" + body);
			return geminiBody("{\"hashtag\": \"닥터피엘\"}");
		}, true, "model-x");

		s.suggest("닥터피엘 Dr.PIEL", "dr.piel_official", Set.of());

		assertThat(sent.get()).contains("model-x:generateContent")
				.contains("닥터피엘 Dr.PIEL").contains("dr.piel_official")
				.contains("responseSchema").contains("\"temperature\":0");
	}

	/** 표시명이 비어도 계정명만으로 호출한다 — 프롬프트가 "표시명 없음" 분기를 담당한다. */
	@Test
	void 표시명이_null이어도_계정명으로_호출한다() {
		AtomicReference<String> sent = new AtomicReference<>();
		var s = new BrandHashtagSuggester((path, body) -> {
			sent.set(body);
			return geminiBody("{\"hashtag\": \"drpiel\"}");
		}, true, "model-x");

		assertThat(s.suggest(null, "dr.piel_official", Set.of())).contains("drpiel");
		assertThat(sent.get()).contains("dr.piel_official");
	}

	@Test
	void 계정명이_없으면_호출하지_않는다() {
		var s = suggester((path, body) -> {
			throw new AssertionError("계정명 없이는 호출하면 안 된다");
		});

		assertThat(s.suggest("표시명", null, Set.of())).isEmpty();
		assertThat(s.suggest("표시명", "  ", Set.of())).isEmpty();
	}

	@Test
	void LLM_미설정이면_호출하지_않는다() {
		var s = new BrandHashtagSuggester((path, body) -> {
			throw new AssertionError("미설정 상태로 호출하면 안 된다");
		}, false, "model-x");

		assertThat(s.suggest("표시명", "brand", Set.of())).isEmpty();
	}

	@Test
	void 응답_본문이_없으면_예외다() {
		var s = suggester((path, body) -> "{\"candidates\":[]}");

		assertThatThrownBy(() -> s.suggest("표시명", "brand", Set.of()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 본문이_JSON이_아니면_예외다() {
		var s = suggester((path, body) -> geminiBody("이건 JSON이 아니다"));

		assertThatThrownBy(() -> s.suggest("표시명", "brand", Set.of()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void hashtag_필드가_없으면_예외다() {
		var s = suggester((path, body) -> geminiBody("{\"tag\": \"닥터피엘\"}"));

		assertThatThrownBy(() -> s.suggest("표시명", "brand", Set.of()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 전송_예외는_그대로_전파한다() {
		var s = suggester((path, body) -> {
			throw new IllegalStateException("전송 실패");
		});

		assertThatThrownBy(() -> s.suggest("표시명", "brand", Set.of()))
				.isInstanceOf(IllegalStateException.class);
	}
}
