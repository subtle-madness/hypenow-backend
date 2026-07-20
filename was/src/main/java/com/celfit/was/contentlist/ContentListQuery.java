package com.celfit.was.contentlist;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 목록 조회 조건 — 프론트 URL 파라미터(§7 2026-07-14 계약)의 홀더.
 * startInstant/cutoff는 KST 날짜 경계를 UTC 오프셋으로 정규화한 값 (D3와 동일 규칙:
 * 기간 = [start 0시, end 다음날 0시), 스냅샷 cutoff = end 다음날 0시).
 */
public record ContentListQuery(
		OffsetDateTime startInstant, OffsetDateTime cutoff,
		String mainCategory, String midCategory, String subCategory,
		String contentType, FollowerRange follower, String adType,
		String distributor, String q, String sort) {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	/** 프론트 follower 구간 값 — 경계는 [min, max) (프론트 FOLLOWER_RANGES와 동일). */
	public enum FollowerRange {
		R3K_10K(3_000, 10_000), R10K_30K(10_000, 30_000), R30K_50K(30_000, 50_000),
		/** 미지원 값 — 공집합 [0, 0)이라 SQL 조건이 항상 false = "알 수 없는 값은 매칭 0" 계약을 자동 충족. */
		UNKNOWN(0, 0);

		final long min;
		final long max;

		FollowerRange(long min, long max) {
			this.min = min;
			this.max = max;
		}

		/** 프론트 값(3k-10k 등) → 구간. 모르는 값은 UNKNOWN(필터 무시가 아니라 매칭 0 — verbatim 계약). */
		public static FollowerRange from(String value) {
			return switch (value) {
				case "3k-10k" -> R3K_10K;
				case "10k-30k" -> R10K_30K;
				case "30k-50k" -> R30K_50K;
				default -> UNKNOWN;
			};
		}
	}

	public static ContentListQuery of(LocalDate startDate, LocalDate endDate,
			String mainCategory, String midCategory, String subCategory,
			String contentType, String follower, String adType,
			String distributor, String q, String sort) {
		return new ContentListQuery(
				startDate.atStartOfDay(KST).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC),
				endDate.plusDays(1).atStartOfDay(KST).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC),
				mainCategory, midCategory, subCategory, contentType,
				follower == null ? null : FollowerRange.from(follower),
				adType, distributor, q, sort == null ? "hype" : sort);
	}
}
