package com.celfit.analytics.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DataSourceConfig {

	@Bean
	@Primary
	@ConfigurationProperties("app.datasource.raw")
	public DataSource rawDataSource() {
		return DataSourceBuilder.create().build();
	}

	@Bean
	@ConfigurationProperties("app.datasource.analysis")
	public DataSource analysisDataSource() {
		return DataSourceBuilder.create().build();
	}

	@Bean
	@Primary
	public JdbcTemplate rawJdbcTemplate(@Qualifier("rawDataSource") DataSource ds) {
		return new JdbcTemplate(ds);
	}

	@Bean
	public JdbcTemplate analysisJdbcTemplate(@Qualifier("analysisDataSource") DataSource ds) {
		return new JdbcTemplate(ds);
	}
}
