package com.celfit.was.v1.content;

import com.celfit.was.v1.common.CacheKeys;
import com.celfit.was.v1.common.V1ApiException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

/**
 * 6.1 쿼리 홀더. 'all'은 null로 정규화(필터 미적용). 검증 위반은 V1ApiException.validation.
 * KST 경계 규칙은 기존 ContentListQuery와 동일: [start 0시, end 다음날 0시).
 */
public record V1ContentQuery(OffsetDateTime startInstant, OffsetDateTime endExclusive,
		String contentType, String mainCategory, String midCategory, String subCategory,
		String follower, String keyword, String adType, String distributorId,
		String sort, int limit, int offset) {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final Set<String> CONTENT_TYPES = Set.of("reels", "feed");
	private static final Set<String> AD_TYPES = Set.of("organic", "sponsored");
	private static final Set<String> SORTS = Set.of("hype", "latest", "views");
	private static final Set<String> FOLLOWERS = Set.of("3k-10k", "10k-30k", "30k-50k");
	private static final Set<String> MAIN_CATEGORIES =
			Set.of("skincare", "suncare", "makeup", "cleansing", "haircare", "fragrance");

	public static V1ContentQuery of(LocalDate startDate, LocalDate endDate, String contentType,
			String mainCategory, String midCategory, String subCategory, String follower,
			String keyword, String adType, String distributorId, String sort, Integer limit,
			Integer offset) {
		if (startDate.isAfter(endDate)) {
			throw V1ApiException.validation("조회 기간이 올바르지 않습니다.");
		}
		String ct = defaulted(contentType, "reels");
		String so = defaulted(sort, "hype");
		String main = allToNull(mainCategory);
		String mid = main == null ? null : allToNull(midCategory);   // main=all이면 무시 (스펙 6.1)
		String sub = mid == null ? null : allToNull(subCategory);    // mid=all이면 무시
		String fo = allToNull(follower);
		String ad = allToNull(adType);
		String dist = allToNull(distributorId);
		require(CONTENT_TYPES.contains(ct), "contentType");
		require(SORTS.contains(so), "sort");
		require(main == null || MAIN_CATEGORIES.contains(main), "mainCategory");
		require(fo == null || FOLLOWERS.contains(fo), "follower");
		require(ad == null || AD_TYPES.contains(ad), "adType");
		int lim = limit == null ? 100 : limit;
		require(lim >= 1 && lim <= 100, "limit");
		int off = offset == null ? 0 : offset;
		require(off >= 0, "offset");
		return new V1ContentQuery(
				startDate.atStartOfDay(KST).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC),
				endDate.plusDays(1).atStartOfDay(KST).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC),
				ct, main, mid, sub, fo, blankToNull(keyword), ad, dist, so, lim, off);
	}

	/** 캐시 키(스펙 §4) — of()가 정규화를 끝낸 컴포넌트 전체의 toString 축약. record toString은 결정적. */
	public String cacheKey() {
		return CacheKeys.sha256(toString());
	}

	/** 다음 페이지 쿼리 — 프리페치용(스펙 §5). */
	public V1ContentQuery next() {
		return new V1ContentQuery(startInstant, endExclusive, contentType, mainCategory, midCategory,
				subCategory, follower, keyword, adType, distributorId, sort, limit, offset + limit);
	}

	private static void require(boolean ok, String name) {
		if (!ok) {
			throw V1ApiException.validation(name + " 값이 올바르지 않습니다.");
		}
	}

	private static String allToNull(String v) {
		return (v == null || v.equals("all")) ? null : v;
	}

	private static String defaulted(String v, String def) {
		return (v == null || v.isBlank()) ? def : v;
	}

	private static String blankToNull(String v) {
		return (v == null || v.isBlank()) ? null : v;
	}
}
