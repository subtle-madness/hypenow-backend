package com.celfit.was.v1.stats;

import com.celfit.contract.analysis.LandingStats;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 스펙 6.20 랜딩 통계. followerDistribution의 range는 6.1 follower enum과 동일 문자열, pct 합계는 항상 100. */
public record StatsResponse(Long contentsCount, Long influencersCount, Long totalViews, Long avgViews,
		List<Band> followerDistribution, String updatedAt) {

	private static final DateTimeFormatter ISO_Z = DateTimeFormatter.ISO_INSTANT;

	/** 6.1 follower enum과 동일 문자열 — 뷰의 구간 경계(3k/10k/30k/50k)와 순서가 일치한다. */
	private static final List<String> RANGES = List.of("3k-10k", "10k-30k", "30k-50k");

	public record Band(String range, int pct) {
	}

	public static StatsResponse from(LandingStats s) {
		return new StatsResponse(s.contentsCount(), s.influencersCount(), s.totalViews(), s.avgViews(),
				distribution(s.followers3k10k(), s.followers10k30k(), s.followers30k50k()),
				ISO_Z.format(s.updatedAt().toInstant()));
	}

	/**
	 * 구간별 계정 수 → 백분율. 합계는 항상 100(스펙 6.20) — 단순 반올림은 99/101이 될 수 있어
	 * 최대 잔여(largest remainder) 방식으로 보정한다: 정확 비율을 내림한 뒤 남는 몫을 잔여가 큰 구간부터 1씩 준다.
	 * 잔여가 동률이면 앞 구간(작은 팔로워 구간)이 우선 — 결정적 출력 보장.
	 * 계정이 0명이면 합계 100 규칙보다 "데이터 없음"이 우선이라 전 구간 0을 낸다.
	 */
	static List<Band> distribution(long b3k, long b10k, long b30k) {
		long[] counts = {b3k, b10k, b30k};
		long total = b3k + b10k + b30k;
		if (total <= 0) {
			return RANGES.stream().map(r -> new Band(r, 0)).toList();
		}

		int[] pct = new int[counts.length];
		// 인덱스별 잔여(나머지) — 정수 연산이라 부동소수점 오차가 없다.
		List<Integer> byRemainder = new ArrayList<>();
		for (int i = 0; i < counts.length; i++) {
			pct[i] = (int) (counts[i] * 100 / total);
			byRemainder.add(i);
		}
		// 잔여 내림차순, 동률이면 인덱스 오름차순(앞 구간 우선).
		byRemainder.sort(Comparator.<Integer, Long>comparing(i -> counts[i] * 100 % total).reversed()
				.thenComparing(Comparator.naturalOrder()));

		int remaining = 100 - (pct[0] + pct[1] + pct[2]);
		for (int i = 0; i < remaining; i++) {
			pct[byRemainder.get(i)]++;
		}

		List<Band> bands = new ArrayList<>();
		for (int i = 0; i < RANGES.size(); i++) {
			bands.add(new Band(RANGES.get(i), pct[i]));
		}
		return List.copyOf(bands);
	}
}
