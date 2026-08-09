package com.celfit.was.v1.influencer;

import com.celfit.was.v1.common.CacheKeys;
import com.celfit.was.v1.common.V1ApiException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 6.21 발굴 목록 쿼리 홀더. 'all'은 null로 정규화(필터 미적용), 검증 위반은 V1ApiException.validation
 * (6.1 V1ContentQuery 관용구). q는 쉼표 구분 키워드를 트림해 전부(AND) 부분일치로 매칭한다.
 */
public record V1InfluencerDiscoveryQuery(List<String> keywords, String mainCategory,
		String midCategory, String subCategory, String follower, Integer activityDays,
		String sponsored, boolean contactOpen, String sort, int limit, int offset) {

	private static final Set<String> SORTS = Set.of("reach", "views", "followers", "hype");
	private static final Set<String> FOLLOWERS = Set.of("500-3k", "3k-10k", "10k-30k", "30k-50k");
	private static final Set<String> SPONSORED = Set.of("none", "1-2", "3-5", "6plus");
	private static final Set<String> MAIN_CATEGORIES =
			Set.of("skincare", "suncare", "makeup", "cleansing", "haircare", "fragrance", "esthetic");

	public static V1InfluencerDiscoveryQuery of(String q, String mainCategory, String midCategory,
			String subCategory, String follower, String activity, String sponsored, String contact,
			String sort, Integer limit, Integer offset) {
		String so = defaulted(sort, "reach");
		String main = allToNull(mainCategory);
		String mid = main == null ? null : allToNull(midCategory);   // main=all이면 무시 (스펙 6.21)
		String sub = mid == null ? null : allToNull(subCategory);    // mid=all이면 무시
		String fo = allToNull(follower);
		String ac = allToNull(activity);
		String sp = allToNull(sponsored);
		String co = allToNull(contact);
		require(SORTS.contains(so), "sort");
		require(main == null || MAIN_CATEGORIES.contains(main), "mainCategory");
		require(fo == null || FOLLOWERS.contains(fo), "follower");
		require(ac == null || ac.matches("7d|14d|30d"), "activity");
		require(sp == null || SPONSORED.contains(sp), "sponsored");
		require(co == null || co.equals("open"), "contact");
		int lim = limit == null ? 100 : limit;
		require(lim >= 1 && lim <= 100, "limit");
		int off = offset == null ? 0 : offset;
		require(off >= 0, "offset");
		return new V1InfluencerDiscoveryQuery(keywords(q), main, mid, sub, fo,
				ac == null ? null : Integer.valueOf(ac.substring(0, ac.length() - 1)),
				sp, co != null, so, lim, off);
	}

	/**
	 * 캐시 키(스펙 §4) — of()가 정규화를 끝낸 컴포넌트를 선언 순서 그대로 나열해 단사 인코딩.
	 * 필드 추가 시 여기도 같이(빠뜨리면 다른 조건이 같은 키가 됨). toString() 직접 사용 금지 —
	 * null과 리터럴 "null"이 같은 문자열이 되는 캐시 오염 취약점이 있었다(2026-07-29 리뷰).
	 */
	public String cacheKey() {
		return CacheKeys.sha256(CacheKeys.canonical(keywords, mainCategory, midCategory,
				subCategory, follower, activityDays, sponsored, contactOpen, sort, limit, offset));
	}

	/** 다음 페이지 쿼리 — 프리페치용(스펙 §5). */
	public V1InfluencerDiscoveryQuery next() {
		return new V1InfluencerDiscoveryQuery(keywords, mainCategory, midCategory, subCategory,
				follower, activityDays, sponsored, contactOpen, sort, limit, offset + limit);
	}

	/** 쉼표 분리 후 트림, 빈 조각 제거 — 키워드 안 쉼표는 프론트 입력에서 차단(스펙 6.21). */
	private static List<String> keywords(String q) {
		if (q == null || q.isBlank()) {
			return List.of();
		}
		return Arrays.stream(q.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
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
}
