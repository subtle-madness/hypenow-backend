package com.celfit.analytics.mirror;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class MirrorConfig {

	@Bean
	public MirrorJob mirrorJob(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource) {
		return new MirrorJob(rawJdbcTemplate, analysisDataSource);
	}

	@Bean
	public MirrorRegistry mirrorRegistry() {
		return new MirrorRegistry(List.of());
	}
}
