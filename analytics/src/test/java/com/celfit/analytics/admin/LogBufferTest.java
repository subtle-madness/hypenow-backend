package com.celfit.analytics.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LogBufferTest {

	private final LogBuffer buffer = new LogBuffer();

	@AfterEach
	void tearDown() {
		buffer.unregister();
	}

	@Test
	void analytics_로거의_로그를_최신순으로_보관() {
		buffer.register();
		Logger log = LoggerFactory.getLogger("com.celfit.analytics.admin.LogBufferTest");
		log.info("첫 줄");
		log.warn("둘째 줄");
		assertThat(buffer.lines()).hasSize(2);
		assertThat(buffer.lines().get(0).message()).isEqualTo("둘째 줄");
		assertThat(buffer.lines().get(0).level()).isEqualTo("WARN");
		assertThat(buffer.lines().get(0).logger()).isEqualTo("LogBufferTest");
	}

	@Test
	void 최대_200줄_초과분은_버림() {
		buffer.register();
		Logger log = LoggerFactory.getLogger("com.celfit.analytics.admin.LogBufferTest");
		for (int i = 0; i < 205; i++) {
			log.info("line {}", i);
		}
		assertThat(buffer.lines()).hasSize(200);
		assertThat(buffer.lines().get(0).message()).isEqualTo("line 204");
	}
}
