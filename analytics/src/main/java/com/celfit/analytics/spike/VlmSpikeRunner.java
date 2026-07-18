package com.celfit.analytics.spike;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.config.JobConfig;
import com.celfit.analytics.llm.AnthropicContentAttributeAnalyzer;
import com.celfit.analytics.llm.BeautyTaxonomyLoader;
import com.celfit.analytics.llm.ContentAttributes;
import com.celfit.analytics.llm.LlmClientFactory;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * F-2 스파이크: 실 콘텐츠 썸네일로 VLM 항목별 출력 품질을 검증한다 (스펙 §7).
 * 실 API를 때리므로 수동 실행 전용 (인증은 GoldsetSpikeRunner와 동일 — ANTHROPIC_AUTH_TOKEN/API_KEY):
 *   ./gradlew :analytics:bootRun --args='--analytics.vlm-spike-limit=8 --spring.main.web-application-type=none'
 * 썸네일 서명 URL은 수집 후 ~4일이면 만료라 최신 수집분에서 살아있는 것만 고른다.
 * 상한 10 하드캡 (비용 가드). 판정 결과는 계획 문서(plans/2026-07-14-*)에 기록한다.
 */
@Configuration
@ConditionalOnProperty(name = "analytics.vlm-spike-limit")
public class VlmSpikeRunner {

	record SpikeTarget(String shortCode, String caption, String thumbnailUrl, java.time.OffsetDateTime capturedAt) {
	}

	@Bean
	public CommandLineRunner vlmSpike(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource,
			org.springframework.core.env.Environment env) {
		return args -> {
			int limit = Math.min(10, Integer.parseInt(
					env.getRequiredProperty("analytics.vlm-spike-limit")));
			Predicate<String> alive = JobConfig.headPrecheck();
			// 프리체크 탈락 대비 3배 후보 — 최신 수집순
			var candidates = rawJdbcTemplate.query("""
					SELECT c.short_code, d.caption, d.thumbnail_url, d.captured_at
					FROM analytics.v_base_content c
					JOIN analytics.v_base_detail d USING (content_id)
					WHERE d.thumbnail_url IS NOT NULL
					ORDER BY d.captured_at DESC
					LIMIT ?""",
					(rs, i) -> new SpikeTarget(rs.getString(1), rs.getString(2), rs.getString(3),
							rs.getObject(4, java.time.OffsetDateTime.class)),
					limit * 3);
			var targets = candidates.stream().filter(t -> alive.test(t.thumbnailUrl())).limit(limit).toList();
			System.out.printf("후보 %d건 중 썸네일 생존 %d건으로 스파이크 실행%n", candidates.size(), targets.size());

			var analyzer = new AnthropicContentAttributeAnalyzer(LlmClientFactory.fromEnv(),
					new AnalyticsSettings(rawJdbcTemplate), new BeautyTaxonomyLoader(analysisDataSource));
			ObjectMapper json = new ObjectMapper();
			// 유통사 어휘 검증: 최신 수집분에 유통사 언급 콘텐츠가 없을 때, 살아있는 썸네일 1장에
			// 지정 캡션 파일(예: 만료 썸네일 콘텐츠의 "올영/다이소" 캡션)을 붙여 정식 상호명 정규화를 확인한다.
			// 파일 경로인 이유: 캡션의 공백·특수문자가 CLI 인자 분리에 깨진다.
			String extraCaptionFile = env.getProperty("analytics.vlm-spike-extra-caption-file");
			if (extraCaptionFile != null && !targets.isEmpty()) {
				String extraCaption = java.nio.file.Files.readString(java.nio.file.Path.of(extraCaptionFile));
				System.out.printf("%n=== 유통사 어휘 검증 (썸네일: %s 재사용) ===%n캡션: %.160s%n",
						targets.get(0).shortCode(), extraCaption.replace('\n', ' '));
				ContentAttributes r = analyzer.analyze(extraCaption, targets.get(0).thumbnailUrl());
				System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(r));
				return;
			}
			for (SpikeTarget t : targets) {
				System.out.printf("%n=== %s (수집 %s) ===%n썸네일: %s%n캡션: %.160s%n",
						t.shortCode(), t.capturedAt().toLocalDate(), t.thumbnailUrl(),
						t.caption() == null ? "(없음)" : t.caption().replace('\n', ' '));
				try {
					long start = System.currentTimeMillis();
					ContentAttributes r = analyzer.analyze(t.caption(), t.thumbnailUrl());
					System.out.printf("소요 %dms%n%s%n", System.currentTimeMillis() - start,
							json.writerWithDefaultPrettyPrinter().writeValueAsString(r));
				} catch (Exception e) {
					System.out.printf("실패: %s%n", e.getMessage());
				}
			}
		};
	}
}
