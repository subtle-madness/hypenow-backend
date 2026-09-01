package com.celfit.contract.analysis;

/**
 * 계정 카테고리 믹스 1행 (analysis DB 파생 뷰 account_category_stats — 미러 아님, 07-21 V35).
 * mainGroup은 beauty_taxonomy 대분류 라벨(main_label) — 분석 층이 확정하고 was는 전달만 한다.
 * axis는 그 대분류의 축(beauty|fnb — 2026-09-01 축 인지화). 대분류가 축을 결정하므로
 * mainGroup은 축을 가로질러 중복되지 않는다.
 */
public record AccountCategoryStat(String accountHandle, String mainGroup, Long contentCount,
		String axis) {
}
