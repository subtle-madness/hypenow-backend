package com.celfit.analytics.materialize;

import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Mirrors read-only analytics views from the RAW database into plain tables in the
 * ANALYSIS database, so the results can be browsed directly with any SQL client.
 *
 * Each run is a full refresh: the target table is dropped, recreated from the source
 * view's result-set metadata, and repopulated from scratch.
 */
@Service
public class MaterializationService {

	private static final Logger log = LoggerFactory.getLogger(MaterializationService.class);

	/** source view (RAW db, schema analytics) -> target table (ANALYSIS db, schema public). */
	private static final List<ViewMapping> VIEW_MAPPINGS = List.of(
			new ViewMapping("v_content_ranking", "content_ranking"),
			new ViewMapping("v_category_performance", "category_performance"),
			new ViewMapping("v_creator_performance", "creator_performance"),
			new ViewMapping("v_creator_overperformance", "creator_overperformance"),
			new ViewMapping("v_content_type_performance", "content_type_performance"),
			new ViewMapping("v_ad_performance", "ad_performance"),
			new ViewMapping("v_tier_distribution", "tier_distribution"),
			new ViewMapping("v_timing_performance", "timing_performance"),
			new ViewMapping("v_hashtag_performance", "hashtag_performance"));

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;

	public MaterializationService(
			@Qualifier("rawJdbcTemplate") JdbcTemplate raw,
			@Qualifier("analysisJdbcTemplate") JdbcTemplate analysis) {
		this.raw = raw;
		this.analysis = analysis;
	}

	public record ViewMapping(String view, String table) {
	}

	public record TableCount(String table, long rowCount) {
	}

	public record MaterializationResult(List<TableCount> tables, Instant runAt) {
	}

	public MaterializationResult materializeAll() {
		ensureMetaTable();
		Instant runAt = Instant.now();
		List<TableCount> results = new ArrayList<>();
		for (ViewMapping mapping : VIEW_MAPPINGS) {
			try {
				long rowCount = mirror(mapping.view(), mapping.table());
				log.info("materialized {}: {} rows", mapping.table(), rowCount);
				upsertMeta(mapping.table(), rowCount, runAt);
				results.add(new TableCount(mapping.table(), rowCount));
			} catch (Exception e) {
				log.warn("skipping {} -> {}: {}", mapping.view(), mapping.table(), e.getMessage(), e);
			}
		}
		return new MaterializationResult(results, runAt);
	}

	/**
	 * Mirrors one source view into one target table.
	 *
	 * NOTE: view/table names are only ever taken from the hardcoded VIEW_MAPPINGS list
	 * above (never from external/user input), so building DDL/DML via string
	 * concatenation here is acceptable.
	 */
	private long mirror(String view, String table) {
		List<Column> columns = describeColumns(view);
		if (columns.isEmpty()) {
			throw new IllegalStateException("view " + view + " has no columns");
		}

		analysis.execute("DROP TABLE IF EXISTS " + table);
		analysis.execute(buildCreateTableSql(table, columns));

		List<Map<String, Object>> rows = raw.queryForList("SELECT * FROM analytics." + view);
		if (rows.isEmpty()) {
			return 0L;
		}

		List<String> columnNames = columns.stream().map(Column::name).toList();
		String insertSql = buildInsertSql(table, columnNames);
		List<Object[]> batchArgs = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			Object[] args = new Object[columnNames.size()];
			for (int i = 0; i < columnNames.size(); i++) {
				args[i] = row.get(columnNames.get(i));
			}
			batchArgs.add(args);
		}
		analysis.batchUpdate(insertSql, batchArgs);
		return rows.size();
	}

	private List<Column> describeColumns(String view) {
		return raw.query("SELECT * FROM analytics." + view + " LIMIT 0", rs -> {
			ResultSetMetaData meta = rs.getMetaData();
			List<Column> columns = new ArrayList<>(meta.getColumnCount());
			for (int i = 1; i <= meta.getColumnCount(); i++) {
				columns.add(new Column(meta.getColumnLabel(i), meta.getColumnTypeName(i)));
			}
			return columns;
		});
	}

	private String buildCreateTableSql(String table, List<Column> columns) {
		StringBuilder sql = new StringBuilder("CREATE TABLE ").append(table).append(" (");
		for (int i = 0; i < columns.size(); i++) {
			if (i > 0) {
				sql.append(", ");
			}
			Column column = columns.get(i);
			sql.append(column.name()).append(' ').append(column.pgType());
		}
		return sql.append(')').toString();
	}

	private String buildInsertSql(String table, List<String> columnNames) {
		String cols = String.join(", ", columnNames);
		String placeholders = String.join(", ", columnNames.stream().map(c -> "?").toList());
		return "INSERT INTO " + table + " (" + cols + ") VALUES (" + placeholders + ")";
	}

	private void ensureMetaTable() {
		analysis.execute("""
				CREATE TABLE IF NOT EXISTS materialization_meta (
					table_name text primary key,
					row_count bigint,
					materialized_at timestamptz
				)
				""");
	}

	private void upsertMeta(String table, long rowCount, Instant runAt) {
		analysis.update("""
				INSERT INTO materialization_meta (table_name, row_count, materialized_at)
				VALUES (?, ?, ?)
				ON CONFLICT (table_name) DO UPDATE
					SET row_count = EXCLUDED.row_count,
						materialized_at = EXCLUDED.materialized_at
				""", table, rowCount, Timestamp.from(runAt));
	}

	private record Column(String name, String pgType) {
	}
}
