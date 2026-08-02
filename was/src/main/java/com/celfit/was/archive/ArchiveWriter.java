package com.celfit.was.archive;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 삭제 직전 원본 행을 archive.archived_rows로 이관한다. 행을 애플리케이션으로 끌어올리지 않고
 * INSERT … SELECT로 DB 안에서 끝낸다.
 *
 * <p>fail-closed — 이관이 실패하면 예외가 그대로 전파돼 트랜잭션이 롤백되고 삭제도 일어나지
 * 않는다. 자산 보존이 목적인데 조용히 유실되면 의미가 없기 때문이다. 따라서 호출부에는
 * 반드시 트랜잭션 경계가 있어야 한다.
 */
@Component
public class ArchiveWriter {

	/** whereClause의 named parameter와 충돌하면 안 되는 예약 이름. */
	private static final String REASON_PARAM = "archiveReason";

	private final JdbcClient jdbcClient;

	public ArchiveWriter(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * @param whereClause 원본 테이블 별칭 t를 쓰는 WHERE 절. 코드 상수여야 한다(외부 입력 금지)
	 * @param params      whereClause의 named parameter 값. "archiveReason" 키는 쓸 수 없다
	 */
	public void archive(ArchiveTable table, ArchiveReason reason, String whereClause, Map<String, Object> params) {
		if (params.containsKey(REASON_PARAM)) {
			throw new IllegalArgumentException("예약된 파라미터명이다: " + REASON_PARAM);
		}
		JdbcClient.StatementSpec spec = jdbcClient.sql(buildSql(table, whereClause))
				.param(REASON_PARAM, reason.name());
		for (Map.Entry<String, Object> entry : params.entrySet()) {
			spec = spec.param(entry.getKey(), entry.getValue());
		}
		spec.update();
	}

	private static String buildSql(ArchiveTable table, String whereClause) {
		String pkJson = table.pkColumns().stream()
				.map(column -> "'" + column + "', t." + column)
				.collect(Collectors.joining(", "));
		String userIdExpr = table.userIdExpr() == null ? "NULL::bigint" : table.userIdExpr();
		String payload = table.omitColumns().stream()
				.map(column -> " - '" + column + "'")
				.collect(Collectors.joining("", "to_jsonb(t)", ""));

		return """
				INSERT INTO archive.archived_rows (table_name, row_pk, user_id, payload, archived_reason)
				SELECT '%s', jsonb_build_object(%s), %s, %s, :%s
				  FROM %s t
				 WHERE %s
				""".formatted(table.qualifiedName(), pkJson, userIdExpr, payload, REASON_PARAM,
				table.qualifiedName(), whereClause);
	}
}
