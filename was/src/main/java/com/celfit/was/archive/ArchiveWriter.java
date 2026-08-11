package com.celfit.was.archive;

import java.util.Map;
import java.util.Set;
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
 *
 * <p>이관 메서드는 두 형태로 좁혀져 있다 — "이 유저의 행 전체"(탈퇴)와 "이 PK 한 행"(저장 해제·캠페인
 * 삭제·등록 롤백). 둘 다 술어를 {@link ArchiveTable}이 소유해서 조립하므로, 호출부가 raw SQL 문자열을
 * 넘길 일이 없다. 두 메서드 모두 이관된 행 수를 돌려준다 — whereClause가 대상과 안 맞아 0건이 이관되고
 * 삭제만 커밋되는 사고를 호출부가 확인할 수 있게 하기 위해서다. {@link #verifyMatched}는 그 확인을
 * 강제하는 마지막 관문 — 호출부가 이관 건수와 삭제 건수를 대조해 어긋나면 예외를 던진다.
 *
 * <p>payload는 <b>삭제 직전이 아니라 이관 시점의 스냅샷</b>이다. SELECT가 원본 행을 잠그지 않으므로
 * READ COMMITTED에서 이관과 삭제 사이에 커밋된 UPDATE는 반영되지 않는다. saved_* 2종은 사실상
 * 불변이라 무해하지만 monitoring_items는 갱신되는 행이라(confirmTarget·cancel) 한 필드가 stale일 수
 * 있다. 창이 밀리초 단위라 FOR UPDATE는 넣지 않았다.
 */
@Component
public class ArchiveWriter {

	/** 조립되는 WHERE 절의 named parameter와 충돌하면 안 되는 예약 이름. */
	private static final String REASON_PARAM = "archiveReason";

	private final JdbcClient jdbcClient;

	public ArchiveWriter(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	/**
	 * 이 유저에 속한 행 전체를 이관한다({@link ArchiveTable#userScopeWhere()} 사용).
	 *
	 * @return 이관된 행 수
	 */
	public int archiveUserScope(ArchiveTable table, ArchiveReason reason, long userId) {
		return execute(table, reason, table.userScopeWhere(), Map.of("userId", userId));
	}

	/**
	 * PK로 특정되는 행 1개를 이관한다.
	 *
	 * @param pkValues 키 집합이 {@code table.pkColumns()}와 정확히 일치해야 한다
	 * @return 이관된 행 수
	 */
	public int archiveByPk(ArchiveTable table, ArchiveReason reason, Map<String, Object> pkValues) {
		if (!pkValues.keySet().equals(Set.copyOf(table.pkColumns()))) {
			throw new IllegalArgumentException(
					"pkValues 키가 table.pkColumns()와 다르다. expected=" + table.pkColumns()
							+ ", actual=" + pkValues.keySet());
		}
		String whereClause = table.pkColumns().stream()
				.map(column -> "t." + column + " = :pk_" + column)
				.collect(Collectors.joining(" AND "));
		Map<String, Object> params = pkValues.entrySet().stream()
				.collect(Collectors.toMap(e -> "pk_" + e.getKey(), Map.Entry::getValue));
		return execute(table, reason, whereClause, params);
	}

	/**
	 * 이관 건수와 삭제 건수가 다르면 즉시 실패시킨다 — 조용한 유실을 막는 마지막 관문.
	 *
	 * <p>fail-closed는 예외만 막는다. 아카이브 술어와 삭제 술어가 어긋나 0건이 이관되고 N건이
	 * 삭제되는 경우는 예외 없이 커밋되므로, 건수 대조가 유일한 탐지 수단이다.
	 */
	public void verifyMatched(ArchiveTable table, int archived, int deleted) {
		if (archived != deleted) {
			throw new IllegalStateException(
					"이관 건수와 삭제 건수가 다르다: table=%s, archived=%d, deleted=%d"
							.formatted(table.qualifiedName(), archived, deleted));
		}
	}

	private int execute(ArchiveTable table, ArchiveReason reason, String whereClause, Map<String, Object> params) {
		if (params.containsKey(REASON_PARAM)) {
			throw new IllegalArgumentException("예약된 파라미터명이다: " + REASON_PARAM);
		}
		JdbcClient.StatementSpec spec = jdbcClient.sql(buildSql(table, whereClause))
				.param(REASON_PARAM, reason.name());
		for (Map.Entry<String, Object> entry : params.entrySet()) {
			spec = spec.param(entry.getKey(), entry.getValue());
		}
		return spec.update();
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
