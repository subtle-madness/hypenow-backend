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
	void 예상_밖_category_문자열은_예외() {
		var extractor = new AdDisclosureExtractorGemini(
				(p, b) -> geminiBody("[{\"phrase\":\"x\",\"category\":\"MAYBE\"}]"), "key", "m");
		assertThatThrownBy(() -> extractor.extract("c")).isInstanceOf(IllegalStateException.class);
	}
}
