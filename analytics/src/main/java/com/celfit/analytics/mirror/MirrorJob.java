package com.celfit.analytics.mirror;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 타입 기반 미러 (ARCHITECTURE.md §4-3): raw DB의 뷰를 SELECT → 공유 record로 매핑 →
 * analysis DB 테이블에 TRUNCATE+INSERT 한 트랜잭션(읽는 쪽에 공백 없음).
 * 시작 시 뷰 컬럼↔record 필드를 이름·순서까지 대조해 불일치면 즉시 실패한다.
 */
public final class MirrorJob {

	private static final Map<Class<?>, Class<?>> WRAPPERS = Map.of(
			long.class, Long.class,
			int.class, Integer.class,
			boolean.class, Boolean.class,
			double.class, Double.class,
			short.class, Short.class);

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final TransactionTemplate analysisTx;

	public MirrorJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.analysisTx = new TransactionTemplate(new DataSourceTransactionManager(analysisDataSource));
	}

	/** @return 옮긴 행 수 */
	public <T extends Record> int mirror(MirrorSpec<T> spec) {
		List<T> rows = raw.query("SELECT * FROM " + spec.viewName(),
				(ResultSetExtractor<List<T>>) rs -> readAll(rs, spec));
		String insertSql = insertSql(spec);
		RecordComponent[] components = spec.recordType().getRecordComponents();
		analysisTx.executeWithoutResult(tx -> {
			analysis.update("TRUNCATE TABLE " + spec.tableName());
			analysis.batchUpdate(insertSql, rows, 500, (ps, row) -> {
				for (int i = 0; i < components.length; i++) {
					ps.setObject(i + 1, componentValue(row, components[i]));
				}
			});
		});
		return rows.size();
	}

	private <T extends Record> List<T> readAll(ResultSet rs, MirrorSpec<T> spec) throws SQLException {
		RecordComponent[] components = spec.recordType().getRecordComponents();
		verifyColumns(rs.getMetaData(), components, spec);
		Constructor<T> ctor = canonicalConstructor(spec.recordType(), components);
		List<T> rows = new ArrayList<>();
		while (rs.next()) {
			Object[] args = new Object[components.length];
			for (int i = 0; i < components.length; i++) {
				Class<?> type = components[i].getType();
				args[i] = rs.getObject(i + 1, (Class<?>) WRAPPERS.getOrDefault(type, type));
			}
			try {
				rows.add(ctor.newInstance(args));
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("record 생성 실패: " + spec.recordType().getSimpleName(), e);
			}
		}
		return rows;
	}

	/** 뷰 컬럼(이름·순서) ↔ record 컴포넌트(snake_case 변환) 대조 — 무언 드리프트 차단. */
	private void verifyColumns(ResultSetMetaData meta, RecordComponent[] components, MirrorSpec<?> spec)
			throws SQLException {
		List<String> viewColumns = new ArrayList<>();
		for (int i = 1; i <= meta.getColumnCount(); i++) {
			viewColumns.add(meta.getColumnName(i));
		}
		List<String> recordColumns = Arrays.stream(components)
				.map(c -> toSnakeCase(c.getName()))
				.toList();
		if (!viewColumns.equals(recordColumns)) {
			throw new IllegalStateException(
					"미러 컬럼 불일치 %s: 뷰 %s ↔ record(%s) %s".formatted(
							spec.viewName(), viewColumns, spec.recordType().getSimpleName(), recordColumns));
		}
	}

	private String insertSql(MirrorSpec<?> spec) {
		List<String> columns = Arrays.stream(spec.recordType().getRecordComponents())
				.map(c -> toSnakeCase(c.getName()))
				.toList();
		String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
		return "INSERT INTO %s (%s) VALUES (%s)"
				.formatted(spec.tableName(), String.join(", ", columns), placeholders);
	}

	private <T extends Record> Constructor<T> canonicalConstructor(Class<T> type, RecordComponent[] components) {
		Class<?>[] paramTypes = Arrays.stream(components).map(RecordComponent::getType).toArray(Class[]::new);
		try {
			return type.getDeclaredConstructor(paramTypes);
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException("canonical constructor 없음: " + type.getSimpleName(), e);
		}
	}

	private Object componentValue(Record row, RecordComponent component) {
		try {
			return component.getAccessor().invoke(row);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("record 접근 실패: " + component.getName(), e);
		}
	}

	static String toSnakeCase(String camel) {
		return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
	}
}
