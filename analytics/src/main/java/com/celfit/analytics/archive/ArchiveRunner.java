package com.celfit.analytics.archive;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 이미지 아카이브 기동 트리거 — analytics.archive-on-startup=true일 때만. 잡 빈은 JobConfig. */
@Configuration
@ConditionalOnProperty(name = "analytics.archive-on-startup", havingValue = "true")
public class ArchiveRunner {

	@Bean
	public CommandLineRunner archiveOnStartup(ImageArchiveJob job) {
		return args -> job.run();
	}
}
