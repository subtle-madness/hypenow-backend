package com.celfit.analytics.mirror;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.Content;
import com.celfit.contract.analysis.ContentComment;
import com.celfit.contract.analysis.ContentMetricSnapshot;
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

	/** 미러 대상 등록부 — 서빙 뷰 4종. 컬럼 계약은 각 record의 Javadoc과 V1·V4 마이그레이션 참조. */
	@Bean
	public MirrorRegistry mirrorRegistry() {
		return new MirrorRegistry(List.of(
				new MirrorSpec<>("analytics.v_accounts", "accounts", Account.class),
				new MirrorSpec<>("analytics.v_contents", "contents", Content.class),
				new MirrorSpec<>("analytics.v_content_comments", "content_comments", ContentComment.class),
				new MirrorSpec<>("analytics.v_content_metric_snapshots", "content_metric_snapshots",
						ContentMetricSnapshot.class)));
	}
}
