package com.celfit.was.archive;

import java.util.List;

/**
 * 아카이브 대상 테이블 1개의 메타데이터. 모든 값은 코드 상수라 SQL 조립에 그대로 써도 안전하다
 * (외부 입력이 섞이는 자리는 whereClause의 named parameter뿐).
 *
 * @param qualifiedName  스키마 한정 테이블명. 예: "app.saved_contents"
 * @param pkColumns      PK 컬럼 목록. 복합 PK면 2개 이상 — row_pk jsonb의 키가 된다
 * @param userIdExpr     archived_rows.user_id로 승격할 표현식("t.user_id" 등). 해당 컬럼이 없으면 null
 * @param omitColumns    payload에서 제외할 컬럼(가명화). 없으면 빈 리스트
 * @param userScopeWhere 특정 유저의 행 전체를 고르는 WHERE 절. named parameter는 :userId 하나만 쓴다
 */
public record ArchiveTable(
		String qualifiedName,
		List<String> pkColumns,
		String userIdExpr,
		List<String> omitColumns,
		String userScopeWhere) {
}
