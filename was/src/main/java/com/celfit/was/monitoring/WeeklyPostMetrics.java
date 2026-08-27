package com.celfit.was.monitoring;

/**
 * 주간 다이제스트가 다루는 게시물 1건의 최소 지표(설계 §3) — 산지가 셋이라 공통 형태로 모은다:
 * 브랜드 태그 발견분(brand_tagged_post + 최신 brand_post_snapshot), 해시태그 발견분
 * (brand_hashtag_post 열거 관측값), 수집 종료분(target + 최신 post_snapshot).
 *
 * <p>{@code views}는 산지가 준 원시값이다 — <b>피드 게시물의 조회수는 항상 NULL</b>이라는 규칙
 * (CLAUDE.md 함정)은 표시·합산 단계에서 {@code contentType}으로 한 번 더 접는다
 * ({@code WeeklyDigestAssembler}). 여기서 접지 않는 이유는 산지별로 접는 규칙이 달라지면
 * 합산이 산지에 따라 갈리기 때문이다.
 */
public record WeeklyPostMetrics(String shortCode, String authorUsername, String contentType,
		Long views, Long likes, Long comments) {
}
