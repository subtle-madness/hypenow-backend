package com.celfit.analytics.spike;

import com.anthropic.client.AnthropicClient;
import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.AnthropicCommentClassifier;
import com.celfit.analytics.llm.ClassifiedComment;
import com.celfit.analytics.llm.CommentToClassify;
import com.celfit.analytics.llm.LlmClientFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * F-1 스파이크: 수동 라벨 골드셋(CSV: id,label,text — 헤더 없음, text에 콤마 가능)을
 * 모델별로 분류해 정확도를 비교한다. 실 API를 때리므로 수동 실행 전용(LLM 인증은
 * {@link LlmClientFactory} 참고 — ANTHROPIC_AUTH_TOKEN 또는 ANTHROPIC_API_KEY 필요):
 *   ./gradlew :analytics:bootRun --args='--analytics.goldset-path=/path/goldset.csv --spring.main.web-application-type=none'
 * 비용·정확도 결과로 analytics.llm-model 설정을 확정한다 (ARCHITECTURE §8 미결).
 */
@Configuration
@ConditionalOnProperty(name = "analytics.goldset-path")
public class GoldsetSpikeRunner {

	private static final List<String> MODELS = List.of("claude-opus-4-8", "claude-haiku-4-5");

	record GoldRow(long id, String label, String text) {
	}

	@Bean
	public CommandLineRunner goldsetSpike(JdbcTemplate rawJdbcTemplate,
			org.springframework.core.env.Environment env) {
		return args -> {
			Path path = Path.of(env.getRequiredProperty("analytics.goldset-path"));
			List<GoldRow> gold = new ArrayList<>();
			int lineNo = 0;
			for (String line : Files.readAllLines(path)) {
				lineNo++;
				if (line.isBlank()) continue;
				// 행 단위 방어: malformed 행(컬럼 부족·숫자 아님)은 skip + 경고 — 전체 크래시 방지
				try {
					String[] parts = line.split(",", 3);
					if (parts.length < 3) {
						throw new IllegalArgumentException("컬럼 3개(id,label,text) 미만");
					}
					gold.add(new GoldRow(Long.parseLong(parts[0].trim()), parts[1].trim(), parts[2]));
				} catch (RuntimeException e) {
					System.err.printf("골드셋 %d행 skip (%s): %s%n", lineNo, e.getMessage(), line);
				}
			}
			List<CommentToClassify> input = gold.stream()
					.map(g -> new CommentToClassify(g.id(), g.text())).toList();
			AnthropicClient client = LlmClientFactory.fromEnv();
			for (String model : MODELS) {
				JdbcTemplate raw = rawJdbcTemplate;
				var settings = new AnalyticsSettings(raw) {
					@Override
					public String llmModel() {
						return model;
					}
				};
				long start = System.currentTimeMillis();
				List<ClassifiedComment> results =
						new AnthropicCommentClassifier(client, settings).classify(input);
				long ms = System.currentTimeMillis() - start;
				Map<Long, String> byId = results.stream()
						.collect(Collectors.toMap(ClassifiedComment::id, ClassifiedComment::category));
				long correct = gold.stream().filter(g -> g.label().equals(byId.get(g.id()))).count();
				System.out.printf("%n=== %s ===%n정확도: %d/%d (%.1f%%), 소요: %dms%n",
						model, correct, gold.size(), 100.0 * correct / gold.size(), ms);
				gold.stream().filter(g -> !g.label().equals(byId.get(g.id())))
						.forEach(g -> System.out.printf("  오분류 id=%d: 정답=%s 예측=%s | %s%n",
								g.id(), g.label(), byId.get(g.id()), g.text()));
			}
		};
	}
}
