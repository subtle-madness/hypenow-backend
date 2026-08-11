package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 크롤링 정책 v1(2026-08-09 스펙 §3) — 나이 티어 순수 함수 판정. 경계값(14/30/90/180일)과
 * 주기 경계(3/7/30일), null last_crawled_at 수렴, 자가 치유를 고정한다.
 */
class BrandCrawlPolicyTest {

	private static final Instant NOW = Instant.parse("2026-08-09T03:00:00Z");

	private static Instant daysAgo(long d) {
		return NOW.minus(Duration.ofDays(d));
	}

	@Test
	void 나이_14일_이하는_스윕마다_due() {
		assertThat(BrandCrawlPolicy.due(daysAgo(0), NOW, NOW)).isTrue();
		assertThat(BrandCrawlPolicy.due(daysAgo(14), daysAgo(0), NOW)).isTrue();
	}

	@Test
	void 나이_15_30일은_3일_경과_시_due() {
		assertThat(BrandCrawlPolicy.due(daysAgo(20), daysAgo(2), NOW)).isFalse();
		assertThat(BrandCrawlPolicy.due(daysAgo(20), daysAgo(3), NOW)).isTrue();
	}

	@Test
	void 나이_31_90일은_7일_경과_시_due() {
		assertThat(BrandCrawlPolicy.due(daysAgo(60), daysAgo(6), NOW)).isFalse();
		assertThat(BrandCrawlPolicy.due(daysAgo(60), daysAgo(7), NOW)).isTrue();
	}

	@Test
	void 나이_91_180일은_30일_경과_시_due() {
		assertThat(BrandCrawlPolicy.due(daysAgo(120), daysAgo(29), NOW)).isFalse();
		assertThat(BrandCrawlPolicy.due(daysAgo(120), daysAgo(30), NOW)).isTrue();
	}

	@Test
	void 나이_180일_초과는_영구_제외() {
		// null last_crawled_at이어도 제외 — 발견 시 스냅샷 1회로 종료(스펙 §3)
		assertThat(BrandCrawlPolicy.due(daysAgo(181), null, NOW)).isFalse();
		assertThat(BrandCrawlPolicy.due(daysAgo(365), daysAgo(300), NOW)).isFalse();
	}

	@Test
	void last_crawled_at_null은_추적_범위_안에서_무조건_due() {
		// 마이그레이션 직후 기존 행·미완 수집분의 안전 수렴 경로
		assertThat(BrandCrawlPolicy.due(daysAgo(5), null, NOW)).isTrue();
		assertThat(BrandCrawlPolicy.due(daysAgo(60), null, NOW)).isTrue();
		assertThat(BrandCrawlPolicy.due(daysAgo(180), null, NOW)).isTrue();
	}

	@Test
	void 스윕_공백은_자가_치유된다() {
		// 20일령 게시물, 마지막 크롤 5일 전(스윕이 이틀 빠짐) — 경과 5일 ≥ 주기 3일이라 due
		assertThat(BrandCrawlPolicy.due(daysAgo(20), daysAgo(5), NOW)).isTrue();
	}

	@Test
	void 티어_경계는_상한_포함이다() {
		// 나이 딱 30일 → 3일 주기 티어(30 < age 아님), 딱 90일 → 7일 주기, 딱 180일 → 30일 주기
		assertThat(BrandCrawlPolicy.due(daysAgo(30), daysAgo(3), NOW)).isTrue();
		assertThat(BrandCrawlPolicy.due(daysAgo(30), daysAgo(2), NOW)).isFalse();
		assertThat(BrandCrawlPolicy.due(daysAgo(90), daysAgo(7), NOW)).isTrue();
		assertThat(BrandCrawlPolicy.due(daysAgo(90), daysAgo(6), NOW)).isFalse();
		assertThat(BrandCrawlPolicy.due(daysAgo(180), daysAgo(30), NOW)).isTrue();
		assertThat(BrandCrawlPolicy.due(daysAgo(180), daysAgo(29), NOW)).isFalse();
	}
}
