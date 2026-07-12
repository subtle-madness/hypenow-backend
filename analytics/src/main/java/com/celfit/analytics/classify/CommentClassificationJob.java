package com.celfit.analytics.classify;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.ClassifiedComment;
import com.celfit.analytics.llm.CommentClassificationPort;
import com.celfit.analytics.llm.CommentToClassify;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 댓글 분류 배치 (스펙 §6-1). 미분류 콘텐츠를 상한(비용 가드)까지 골라
 * 콘텐츠 단위로 분류→저장한다. 콘텐츠 단위 delete→insert 한 트랜잭션 = 멱등,
 * 부분 실패 시 해당 콘텐츠만 롤백돼 다음 실행에서 자동 재대상.
 */
public class CommentClassificationJob {

	private static final Logger log = LoggerFactory.getLogger(CommentClassificationJob.class);

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final TransactionTemplate analysisTx;
	private final CommentClassificationPort port;
	private final AnalyticsSettings settings;

	public CommentClassificationJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			CommentClassificationPort port, AnalyticsSettings settings) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.analysisTx = new TransactionTemplate(new DataSourceTransactionManager(analysisDataSource));
		this.port = port;
		this.settings = settings;
	}

	/** @return 처리한 콘텐츠 수 */
	public int run() {
		List<String> classified = analysis.queryForList(
				"SELECT DISTINCT short_code FROM comment_classifications", String.class);
		List<String> targets = raw.queryForList("""
				SELECT DISTINCT short_code FROM analytics.v_content_comments
				ORDER BY short_code""", String.class).stream()
				.filter(sc -> !classified.contains(sc))
				.limit(settings.analyzeBatchLimit())
				.toList();
		String model = settings.llmModel();
		int processed = 0;
		for (String shortCode : targets) {
			List<CommentToClassify> comments = raw.query("""
					SELECT id, body FROM analytics.v_content_comments WHERE short_code = ?""",
					(rs, i) -> new CommentToClassify(rs.getLong(1), rs.getString(2)), shortCode);
			List<ClassifiedComment> results = port.classify(comments);
			analysisTx.executeWithoutResult(tx -> {
				analysis.update("DELETE FROM comment_classifications WHERE short_code = ?", shortCode);
				analysis.batchUpdate(
						"INSERT INTO comment_classifications (id, short_code, ai_category, model) VALUES (?, ?, ?, ?)",
						results, 500, (ps, r) -> {
							ps.setLong(1, r.id());
							ps.setString(2, shortCode);
							ps.setString(3, r.category());
							ps.setString(4, model);
						});
			});
			processed++;
			log.info("classified {} comments for {}", results.size(), shortCode);
		}
		log.info("classification complete ({} contents)", processed);
		return processed;
	}
}
