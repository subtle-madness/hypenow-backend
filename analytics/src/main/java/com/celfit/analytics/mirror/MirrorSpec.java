package com.celfit.analytics.mirror;

/**
 * 미러 대상 1건: raw DB의 뷰 → analysis DB의 테이블, 사이의 자바 그릇은 record.
 * record 컴포넌트(camelCase)를 snake_case로 바꾼 이름·순서가 뷰 컬럼·테이블 컬럼과 일치해야 한다.
 *
 * <p>숫자 경계: toSnakeCase는 숫자-문자 경계에 언더스코어를 넣지 않는다 —
 * {@code recent12AvgLikeCount} → {@code recent12_avg_like_count}({@code recent_12_...} 아님).
 * 뷰 컬럼명을 이 규칙에 맞출 것.
 *
 * <p>타입: record 컴포넌트 타입은 뷰 컬럼의 SQL 타입 패밀리와 일치해야 한다 —
 * 예: {@code numeric} 컬럼은 BigDecimal(Long으로 읽으면 런타임 PSQLException).
 *
 * <p>viewName/tableName은 SQL에 그대로 연결되므로 <b>코드 상수만</b> 허용 — 설정·외부 입력에서 파생 금지.
 */
public record MirrorSpec<T extends Record>(String viewName, String tableName, Class<T> recordType) {
}
