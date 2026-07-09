package com.celfit.analytics.materialize;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Runs the view -> table materialization once on startup, then exits cleanly
 * (this app has no web server / long-running work beyond the materialization job).
 */
@Component
public class MaterializationRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(MaterializationRunner.class);

	private final MaterializationService materializationService;
	private final ConfigurableApplicationContext context;
	private final boolean materializeOnStartup;

	public MaterializationRunner(
			MaterializationService materializationService,
			ConfigurableApplicationContext context,
			@Value("${analytics.materialize-on-startup:true}") boolean materializeOnStartup) {
		this.materializationService = materializationService;
		this.context = context;
		this.materializeOnStartup = materializeOnStartup;
	}

	@Override
	public void run(ApplicationArguments args) {
		final int exitCode;
		int code = 0;
		try {
			if (materializeOnStartup) {
				MaterializationService.MaterializationResult result = materializationService.materializeAll();
				logSummary(result);
			} else {
				log.info("analytics.materialize-on-startup=false, skipping materialization");
			}
		} catch (Exception e) {
			log.error("materialization run failed", e);
			code = 1;
		} finally {
			exitCode = code;
			System.exit(SpringApplication.exit(context, () -> exitCode));
		}
	}

	private void logSummary(MaterializationService.MaterializationResult result) {
		log.info("materialization summary (run at {}):", result.runAt());
		log.info(String.format("%-30s | %s", "table", "rows"));
		for (MaterializationService.TableCount tableCount : result.tables()) {
			log.info(String.format("%-30s | %d", tableCount.table(), tableCount.rowCount()));
		}
	}
}
