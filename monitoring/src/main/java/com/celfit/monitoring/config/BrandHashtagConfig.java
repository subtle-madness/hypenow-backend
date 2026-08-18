package com.celfit.monitoring.config;

import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.llm.BrandMentionJudge;
import com.celfit.monitoring.llm.GeminiHttp;
import com.celfit.monitoring.service.BrandHashtagCollectService;
import com.celfit.monitoring.store.BrandHashtagRepository;
import com.celfit.monitoring.store.BrandRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 브랜드 해시태그 스윕 배선(스펙 2026-08-11 §3·§4) — judge·service를 여기서 조립한다.
 * BrandMentionJudge는 analytics 전 축과 통일된 모델(gemini-3.1-flash-lite, 2.5-flash 아님)을
 * 쓴다 — LLM 미설정이면 judge 내부가 fail-closed(UNCERTAIN)로 접으므로 빈 값 기본을 허용한다.
 * 전송({@link GeminiHttp})·활성 여부({@link LlmTransportConfig.LlmEnabled})는
 * {@link LlmTransportConfig}가 조립한 공유 빈을 그대로 쓴다(2026-08-18 Vertex 전환).
 */
@Configuration
public class BrandHashtagConfig {

	@Bean
	public BrandMentionJudge brandMentionJudge(GeminiHttp geminiHttp, LlmTransportConfig.LlmEnabled llmEnabled,
			@Value("${monitoring.brand.hashtag.judge-model:gemini-3.1-flash-lite}") String model) {
		return new BrandMentionJudge(geminiHttp, llmEnabled.value(), model);
	}

	@Bean
	public BrandHashtagCollectService brandHashtagCollectService(HikerClient hiker,
			BrandCallContext callContext, BrandHashtagRepository repo, BrandMentionJudge judge,
			BrandRepository brands,
			@Value("${monitoring.brand.hashtag.window-days:90}") int windowDays,
			@Value("${monitoring.brand.hashtag.max-pages:4}") int maxPages) {
		return new BrandHashtagCollectService(hiker, callContext, repo, judge, brands, windowDays, maxPages);
	}
}
