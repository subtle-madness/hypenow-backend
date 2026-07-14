package com.celfit.analytics.mirror;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 기동 시 등록부의 미러를 순서대로 실행하는 1회성 배치. */
@Component
@ConditionalOnProperty(name = "analytics.mirror-on-startup", havingValue = "true")
public class MirrorRunner implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(MirrorRunner.class);

	private final MirrorJob job;
	private final MirrorRegistry registry;

	public MirrorRunner(MirrorJob job, MirrorRegistry registry) {
		this.job = job;
		this.registry = registry;
	}

	@Override
	public void run(String... args) {
		for (MirrorSpec<?> spec : registry.specs()) {
			int rows = job.mirror(spec);
			log.info("mirrored {} rows: {} -> {}", rows, spec.viewName(), spec.tableName());
		}
		log.info("mirror complete ({} targets)", registry.specs().size());
	}
}
