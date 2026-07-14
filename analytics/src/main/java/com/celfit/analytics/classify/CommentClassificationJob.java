package com.celfit.analytics.classify;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.llm.ClassifiedComment;
import com.celfit.analytics.llm.CommentClassificationPort;
import com.celfit.analytics.llm.CommentToClassify;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 댓글 분류 배치 (스펙 §6-1). 미분류 콘텐츠를 상한(비용 가드)까지 골라
 * 콘텐츠 단위로 분류→저장한다. 콘텐츠 단위 delete→insert 한 트랜잭션 = 멱등,
 * 부분 실패 시 해당 콘텐츠만 건너뛰고 다음 실행에서 자동 재대상.
 * LLM 출력은 저장 전에 입력 id 기준으로 정합한다 (환각 id 버림·누락 etc 합성·중복 첫 결과만).
 */
public class CommentClassificationJob {

	private static final Logger log = LoggerFactory.getLogger(CommentClassificationJob.class);

	/**
	 * 콘텐츠 1건당 분류에 넣는 댓글 수 상한 — 좋아요 많은 순 우선.
	 * 수집 상한은 crawler 설정이라 여기서 독립적으로 방어한다 (프롬프트 크기·비용 가드).
	 */
	static final int MAX_COMMENTS_PER_CONTENT = 200;

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

	/** @return 처리(성공)한 콘텐츠 수 */
	public int run() {
		Set<String> classified = new HashSet<>(analysis.queryForList(
				"SELECT DISTINCT short_code FROM comment_classifications", String.class));
		List<String> targets = raw.queryForList("""
				SELECT DISTINCT short_code FROM analytics.v_content_comments
				ORDER BY short_code""", String.class).stream()
				.filter(sc -> !classified.contains(sc))
				.limit(settings.analyzeBatchLimit())
				.toList();
		String model = settings.llmModel();
		int processed = 0;
		int failed = 0;
		for (String shortCode : targets) {
			try {
				List<CommentToClassify> comments = raw.query("""
						SELECT id, body FROM analytics.v_content_comments WHERE short_code = ?
						ORDER BY like_count DESC NULLS LAST, id LIMIT ?""",
						(rs, i) -> new CommentToClassify(rs.getLong(1), rs.getString(2)),
						shortCode, MAX_COMMENTS_PER_CONTENT);
				List<ClassifiedComment> results = reconcile(shortCode, comments, port.classify(comments));
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
			catch (Exception e) {
				failed++;
				log.error("분류 실패 — {} 건너뜀 (다음 실행에서 재대상)", shortCode, e);
			}
		}
		log.info("classification complete ({} contents, {} failed)", processed, failed);
		return processed;
	}

	/**
	 * LLM 출력을 입력 id 집합 기준으로 정합한다 — 정합 후 결과 수 == 입력 수 보장.
	 * 입력에 없는 id는 버리고(환각 → 고아 행/PK 충돌 차단), 누락된 id는 etc로 합성하며
	 * (조용한 유실 차단), 같은 id 중복 결과는 첫 번째만 쓴다.
	 */
	private List<ClassifiedComment> reconcile(String shortCode,
			List<CommentToClassify> input, List<ClassifiedComment> results) {
		Set<Long> inputIds = new HashSet<>();
		for (CommentToClassify c : input) {
			inputIds.add(c.id());
		}
		Map<Long, String> byId = new HashMap<>();
		int dropped = 0;
		for (ClassifiedComment r : results) {
			if (!inputIds.contains(r.id())) {
				dropped++;
				continue;
			}
			byId.putIfAbsent(r.id(), r.category());
		}
		List<ClassifiedComment> reconciled = new ArrayList<>(input.size());
		int synthesized = 0;
		for (CommentToClassify c : input) {
			String category = byId.get(c.id());
			if (category == null) {
				category = "etc";
				synthesized++;
			}
			reconciled.add(new ClassifiedComment(c.id(), category));
		}
		if (dropped > 0 || synthesized > 0) {
			log.warn("LLM 출력 정합 — {}: 입력에 없는 id {}건 버림, 누락 {}건 etc 합성",
					shortCode, dropped, synthesized);
		}
		return reconciled;
	}
}
