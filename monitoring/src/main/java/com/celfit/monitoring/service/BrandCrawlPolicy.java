package com.celfit.monitoring.service;

import java.time.Duration;
import java.time.Instant;

/**
 * 브랜드 크롤링 정책 v1(2026-08-09 스펙 §3) — 게시물 나이 기반 티어의 순수 함수 판정.
 * 입력은 taken_at·last_crawled_at·현재 시각 3개뿐, 저장된 티어 상태 없음(정책 원칙).
 * 티어 경계·주기는 정책 상수라 코드에 둔다(런타임 토글 불필요 — v1.1 적응형 조정이 오면
 * 이 클래스만 바뀐다). 판정 기준은 항상 taken_at(게시물 나이)이다 — 발견 시각이 아니다.
 * 주기 경과는 {@link #INTERVAL_SLACK}(12h) 여유를 두고 본다 — 일일 스윕 슬롯 드리프트로 하루
 * 밀리는 경계 레이스 방지(필드 주석).
 */
public final class BrandCrawlPolicy {

	/** 매일 티어 상한(0~14일) — 스윕 최소 열거 깊이이기도 하다(신규 태그 발견 보장 — 스펙 §4). */
	public static final Duration DAILY_MAX_AGE = Duration.ofDays(14);

	/** 추적 상한(180일) — 초과 게시물은 발견 시 스냅샷 1회로 종료(영구 제외 — 스펙 §3·§4). */
	public static final Duration TRACKED_MAX_AGE = Duration.ofDays(180);

	private static final Duration TIER2_MAX_AGE = Duration.ofDays(30);
	private static final Duration TIER2_INTERVAL = Duration.ofDays(3);
	private static final Duration TIER3_MAX_AGE = Duration.ofDays(90);
	private static final Duration TIER3_INTERVAL = Duration.ofDays(7);
	private static final Duration TIER4_INTERVAL = Duration.ofDays(30);

	/**
	 * 주기 판정 여유(2026-08-23) — 스윕은 하루 1회 같은 시각(KST 02:00)에 브랜드를 순회하는데
	 * 경과를 주기와 초 단위로 정확히 비교하면, 오늘 스윕이 직전 touch 시각보다 몇 초~몇 분만
	 * 일찍 도착한 브랜드의 게시물은 전부 건너뛰고 그날 하루 "미처리"로 보였다가 다음 날에야
	 * 수습된다(08-23 운영 실측: 3일 티어 262건 전부 이 패턴 — 직전 touch 02:07~02:34, 오늘
	 * 스윕 통과가 그보다 수 분 일렀다). 12시간은 슬롯 드리프트(분 단위)를 흡수하면서 전날
	 * 스윕(경과 ≈ 주기 − 1일)은 여전히 건너뛰는 중간값이다 — 실질 주기가 줄지 않는다.
	 * 대시보드의 "미처리"(정확 주기 기준)는 그대로 둔다: 여유로 잡힌 게시물은 오늘 touch돼
	 * "오늘 처리"로 들어가므로, 미처리 > 0은 이제 수집 실패·미커버 종료만 뜻한다.
	 */
	static final Duration INTERVAL_SLACK = Duration.ofHours(12);

	private BrandCrawlPolicy() {}

	/**
	 * 이 게시물이 지금 갱신 기한(due)인가 — 나이 티어별 last_crawled_at 경과 판정.
	 * last_crawled_at null은 추적 범위 안에서 무조건 due(마이그레이션 직후 기존 행·미완
	 * 수집분의 안전 수렴 — 스펙 §3·§6).
	 */
	public static boolean due(Instant takenAt, Instant lastCrawledAt, Instant now) {
		Duration age = Duration.between(takenAt, now);
		if (age.compareTo(TRACKED_MAX_AGE) > 0) {
			return false;
		}
		if (lastCrawledAt == null) {
			return true;
		}
		if (age.compareTo(DAILY_MAX_AGE) <= 0) {
			return true;
		}
		Duration sinceCrawl = Duration.between(lastCrawledAt, now);
		if (age.compareTo(TIER2_MAX_AGE) <= 0) {
			return elapsed(sinceCrawl, TIER2_INTERVAL);
		}
		if (age.compareTo(TIER3_MAX_AGE) <= 0) {
			return elapsed(sinceCrawl, TIER3_INTERVAL);
		}
		return elapsed(sinceCrawl, TIER4_INTERVAL);
	}

	/** 주기 경과 판정 — {@link #INTERVAL_SLACK}만큼 모자라도 경과로 본다. */
	private static boolean elapsed(Duration sinceCrawl, Duration interval) {
		return sinceCrawl.compareTo(interval.minus(INTERVAL_SLACK)) >= 0;
	}
}
