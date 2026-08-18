package com.celfit.monitoring.ad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.monitoring.ad.AdDisclosureExtractor.Category;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AdDisclosureExtractorGeminiTest {

	private static String geminiBody(String disclosuresJson) {
		return """
				{"candidates":[{"content":{"parts":[{"text":"{\\"disclosures\\":%s}"}]}}]}"""
				.formatted(disclosuresJson.replace("\"", "\\\""));
	}

	/** text 필드 값을 원문 그대로(잘린 JSON 포함) 안전하게 이스케이프해 감싼다. */
	private static String geminiBodyWithRawText(String textFieldRawJson) {
		String escaped = textFieldRawJson.replace("\\", "\\\\").replace("\"", "\\\"");
		return """
				{"candidates":[{"content":{"parts":[{"text":"%s"}]}}]}""".formatted(escaped);
	}

	@Test
	void 문구와_카테고리를_파싱한다() {
		var extractor = new AdDisclosureExtractorGemini(
				(path, body) -> geminiBody("[{\"phrase\":\"#광고\",\"category\":\"CLEAR\"}]"), "key", "model-x");
		List<AdDisclosureExtractor.Disclosure> result = extractor.extract("오늘의 룩 #광고");
		assertThat(result).containsExactly(new AdDisclosureExtractor.Disclosure("#광고", Category.CLEAR));
	}

	@Test
	void 여러_문구를_파싱한다() {
		var extractor = new AdDisclosureExtractorGemini((path, body) -> geminiBody(
				"[{\"phrase\":\"체험단\",\"category\":\"AMBIGUOUS\"},{\"phrase\":\"Sponsor\",\"category\":\"FOREIGN\"}]"),
				"key", "m");
		assertThat(extractor.extract("c")).hasSize(2);
	}

	@Test
	void 빈_배열은_빈_리스트() {
		var extractor = new AdDisclosureExtractorGemini((path, body) -> geminiBody("[]"), "key", "m");
		assertThat(extractor.extract("광고 표기 없음")).isEmpty();
	}

	@Test
	void 요청_경로와_바디에_모델_캡션이_실린다() {
		AtomicReference<String> sent = new AtomicReference<>();
		var extractor = new AdDisclosureExtractorGemini((path, body) -> {
			sent.set(path + "\n" + body);
			return geminiBody("[]");
		}, "key", "model-x");
		extractor.extract("오늘의 룩 #광고");
		assertThat(sent.get()).contains("model-x:generateContent").contains("오늘의 룩 #광고")
				.contains("responseSchema");
	}

	@Test
	void api_키가_비어있으면_예외로_실패한다() {
		// BrandMentionJudge와 달리 fail-closed(UNCERTAIN)로 접지 않는다 — verdict NULL 유지가 계약(스펙 §5)
		var extractor = new AdDisclosureExtractorGemini((p, b) -> {
			throw new AssertionError("키 없이는 호출하면 안 된다");
		}, "", "m");
		assertThatThrownBy(() -> extractor.extract("c")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void candidates_본문이_없으면_예외() {
		var extractor = new AdDisclosureExtractorGemini((p, b) -> "{}", "key", "m");
		assertThatThrownBy(() -> extractor.extract("c")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 본문_json에_disclosures_필드가_없으면_예외() {
		var extractor = new AdDisclosureExtractorGemini(
				(p, b) -> """
						{"candidates":[{"content":{"parts":[{"text":"{\\"foo\\":1}"}]}}]}""",
				"key", "m");
		assertThatThrownBy(() -> extractor.extract("c")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void disclosures가_배열이_아니면_예외() {
		var extractor = new AdDisclosureExtractorGemini(
				(p, b) -> """
						{"candidates":[{"content":{"parts":[{"text":"{\\"disclosures\\":\\"oops\\"}"}]}}]}""",
				"key", "m");
		assertThatThrownBy(() -> extractor.extract("c")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 본문_json이_중간에_잘리면_예외() {
		// maxOutputTokens 초과로 응답이 잘리는 실제 시나리오 — text 필드 값 자체가 불완전한 JSON
		var extractor = new AdDisclosureExtractorGemini(
				(p, b) -> geminiBodyWithRawText("{\"disclosures\":[{\"phrase\":\"#광"), "key", "m");
		assertThatThrownBy(() -> extractor.extract("c")).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("#광");
	}

	@Test
	void 예상_밖_category_문자열은_예외() {
		var extractor = new AdDisclosureExtractorGemini(
				(p, b) -> geminiBody("[{\"phrase\":\"x\",\"category\":\"MAYBE\"}]"), "key", "m");
		assertThatThrownBy(() -> extractor.extract("c")).isInstanceOf(IllegalStateException.class);
	}
}
