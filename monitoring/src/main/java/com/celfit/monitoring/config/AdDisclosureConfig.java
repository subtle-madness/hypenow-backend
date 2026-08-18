package com.celfit.monitoring.config;

import com.celfit.monitoring.ad.AdDisclosureExtractorGemini;
import com.celfit.monitoring.ad.AdDisclosureJudgeService;
import com.celfit.monitoring.llm.GeminiHttp;
import com.celfit.monitoring.store.BrandPostMetaRepository;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 광고 표기 판정 배선(스펙 2026-08-17 §5·§7) — BrandHashtagConfig와 같은 조립 패턴.
 * LLM 콜은 Hiker 보강 워커 풀(brandEnrichWorkerPool)과 <b>분리된 전용 소형 풀</b>로 나간다 —
 * LLM 지연(초 단위)이 게시자·댓글 수집 처리량을 잠식하지 않게 한다(스펙 §7).
 * 전송({@link GeminiHttp})·활성 여부는 {@link LlmTransportConfig}가 조립한 공유 빈을 그대로 쓴다
 * (2026-08-18 Vertex 전환). 백필 방어선(서킷브레이커·1회 상한)도 08-18 429 폭주 실측 계기로 추가.
 */
@Configuration
public class AdDisclosureConfig {

	@Bean
	public AdDisclosureExtractorGemini adDisclosureExtractor(GeminiHttp geminiHttp,
			LlmTransportConfig.LlmEnabled llmEnabled,
			@Value("${monitoring.brand.ad-disclosure.judge-model:gemini-3.1-flash-lite}") String model) {
		return new AdDisclosureExtractorGemini(geminiHttp, llmEnabled.value(), model);
	}

	@Bean(name = "adDisclosureWorkerPool")
	public Executor adDisclosureWorkerPool(
			@Value("${monitoring.brand.ad-disclosure.concurrency:4}") int concurrency) {
		AtomicInteger seq = new AtomicInteger();
		return Executors.newFixedThreadPool(concurrency, r -> {
			Thread t = new Thread(r, "brand-ad-disclosure-" + seq.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}

	@Bean
	public AdDisclosureJudgeService adDisclosureJudgeService(BrandPostMetaRepository metaRepo,
			AdDisclosureExtractorGemini extractor, @Qualifier("adDisclosureWorkerPool") Executor worker,
			@Value("${monitoring.brand.ad-disclosure.llm-failure-abort-threshold:10}") int llmFailureAbortThreshold,
			@Value("${monitoring.brand.ad-disclosure.backfill-max-per-run:1000}") int backfillMaxPerRun) {
		return new AdDisclosureJudgeService(metaRepo, extractor, worker, llmFailureAbortThreshold,
				backfillMaxPerRun);
	}
}
