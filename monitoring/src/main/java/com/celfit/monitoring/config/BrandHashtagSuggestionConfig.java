package com.celfit.monitoring.config;

import com.celfit.monitoring.llm.BrandHashtagSuggester;
import com.celfit.monitoring.llm.GeminiHttp;
import com.celfit.monitoring.service.BrandHashtagSuggestionService;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.TaggedPostRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 브랜드 해시태그 제안 배선(2026-09-03 자동 시드 재설계) — {@link AdDisclosureConfig}와 같은 조립
 * 패턴이다. 전송({@link GeminiHttp})·활성 여부는 {@link LlmTransportConfig}가 조립한 공유 빈을
 * 그대로 쓴다(새 HTTP 클라이언트를 만들지 않는다).
 *
 * <p>전용 executor는 두지 않는다 — 제안은 was의 내부 GET 1건 안에서 동기로 끝나는 브랜드 생애
 * 1회짜리 계산이고, 그 호출부(was 훅)가 이미 best-effort로 격리돼 있다.
 */
@Configuration
public class BrandHashtagSuggestionConfig {

	@Bean
	public BrandHashtagSuggester brandHashtagSuggester(GeminiHttp geminiHttp,
			LlmTransportConfig.LlmEnabled llmEnabled,
			@Value("${monitoring.brand.hashtag-seed.model:gemini-3.1-flash-lite}") String model) {
		return new BrandHashtagSuggester(geminiHttp, llmEnabled.value(), model);
	}

	@Bean
	public BrandHashtagSuggestionService brandHashtagSuggestionService(TaggedPostRepository taggedPosts,
			BrandRepository brands, BrandHashtagSuggester suggester,
			BrandHashtagSeedSettings settings, MeterRegistry meterRegistry) {
		return new BrandHashtagSuggestionService(taggedPosts, brands, suggester, settings, meterRegistry);
	}
}
