package com.celfit.analytics.mirror;

/**
 * 미러 대상 1건: raw DB의 뷰 → analysis DB의 테이블, 사이의 자바 그릇은 record.
 * record 컴포넌트(camelCase)를 snake_case로 바꾼 이름·순서가 뷰 컬럼·테이블 컬럼과 일치해야 한다.
 */
public record MirrorSpec<T extends Record>(String viewName, String tableName, Class<T> recordType) {
}
