package com.celfit.contract.analysis;

/**
 * 계정 카테고리 믹스 1행 (미러: analytics.v_account_category_stats → account_category_stats).
 * mainGroup은 crawler 분류 어휘 — was는 전달만.
 */
public record AccountCategoryStat(String accountHandle, String mainGroup, Long contentCount) {
}
