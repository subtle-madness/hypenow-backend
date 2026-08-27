package com.celfit.was.v1.perfdashboard;

import com.celfit.was.v1.brandmonitoring.BrandAccountType;
import com.celfit.was.v1.common.V1ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 목록·인플루언서·(후속) growth 표면이 공유하는 쿼리 정규화·술어 — 값 공간·기본값·400 메시지가
 * 표면 간에 갈라지지 않게 한 곳에 둔다(PR ① 최종 리뷰 인계).
 *
 * <p>표면마다 정의가 갈리는 것(정렬 키·참여율·스냅샷 모드처럼 목록 전용인 어휘)은 여기 두지
 * 않는다 — 공용화의 기준은 "값 공간과 400 메시지가 표면 간에 같아야 하는가"다.
 */
final class DashboardQueries {

	static final String FILTER_ALL = "all";
	/** campaignId 전용 값 — "캠페인에 묶이지 않은 콘텐츠만"(값이 아니라 부재를 고르는 필터라 별도 어휘). */
	static final String CAMPAIGN_NONE = "none";

	/** 페이지 크기 상한·기본값 — 브랜드 목록(PR #602)과 같은 캡이다. */
	static final int PAGE_LIMIT_MAX = 100;
	static final int PAGE_LIMIT_DEFAULT = 100;

	private DashboardQueries() {
	}

	// ---------- 필터 ----------

	static boolean withinUploadWindow(LocalDate uploadedOn, LocalDate from, LocalDate to) {
		if (from == null && to == null) {
			return true;
		}
		if (uploadedOn == null) {
			// 업로드일을 모르는 콘텐츠(collecting·detecting 등 post 없는 아이템)는 기간 판정이 불가라
			// data에서 제외한다 — 다만 statusCounts 모수에는 그대로 남는다(§7-1).
			return false;
		}
		return (from == null || !uploadedOn.isBefore(from)) && (to == null || !uploadedOn.isAfter(to));
	}

	/**
	 * 브랜드 범위 술어 — 복수 {@code accountIds}가 단수 {@code brandAccountId}를 이긴다(2026-08-27
	 * §2, 신규약 우선). 둘 다 없으면 브랜드 범위 제한이 없다.
	 */
	static boolean matchesBrand(String brandAccountId, String brandFilter, Set<String> accountIdsFilter) {
		if (accountIdsFilter != null) {
			return accountIdsFilter.contains(brandAccountId);
		}
		return brandFilter == null || brandFilter.equals(brandAccountId);
	}

	/**
	 * 캠페인 필터 술어 정본 — 카드(목록)와 ref(비교)가 같은 규칙을 쓴다. {@code none}은 값이 아니라
	 * 부재를 고르는 어휘라 별도 분기다(위 {@link #CAMPAIGN_NONE} 참고).
	 */
	static boolean matchesCampaign(String campaignId, String filter) {
		if (filter == null) {
			return true;
		}
		return CAMPAIGN_NONE.equals(filter) ? campaignId == null : Objects.equals(campaignId, filter);
	}

	/**
	 * accountType 필터(08-12) — {@code all}(=null)은 전량, {@code competitor}는 경쟁사 구독 소속만,
	 * <b>미지정·own은 "경쟁사만 제외"</b>다.
	 *
	 * <p>미지정이 "own 브랜드만"이 아닌 이유: 이 응답에는 브랜드에 귀속되지 않는 레거시 개인 추적
	 * 콘텐츠(brandAccountId null)가 섞여 있어, 문자 그대로 own만 남기면 경쟁사를 하나도 등록하지
	 * 않은 유저의 성과 요약 숫자까지 줄어든다. 요청서의 의도(경쟁사가 내 성과를 오염시키지 않게)는
	 * 경쟁사만 빼는 것으로 충족된다(스펙 §5).
	 */
	static boolean matchesAccountType(String brandAccountId, String filter, Set<String> competitorIds) {
		boolean competitor = brandAccountId != null && competitorIds.contains(brandAccountId);
		if (BrandAccountType.COMPETITOR.equals(filter)) {
			return competitor;
		}
		if (filter == null) {
			return true;   // all — 미지정과 갈라지는 지점이다(normalizeAccountType 참고).
		}
		return !competitor;
	}

	// ---------- 정규화 ----------

	/**
	 * accountType 전용 정규화 — 다른 필터와 달리 미지정과 {@code all}이 다르다(미지정은 경쟁사 제외가
	 * 기본, all은 전량). 그래서 공용 {@link #normalizeFilter(String, String, String...)}를 쓰지 않는다:
	 * 그쪽에 태우면 미지정이 곧 전량이 되어 경쟁사 콘텐츠가 기본 성과 요약을 오염시킨다.
	 *
	 * <p>단, <b>미지정이면서 브랜드를 집어 물은 조회</b>({@code brandAccountId} 또는 {@code accountIds}
	 * 명시)는 전량(all)이다 — 특정 브랜드를 지정한 요청에 경쟁사 제외 기본값까지 겹쳐 걸면 경쟁사
	 * 브랜드 조회가 조용히 빈다(08-12 리뷰, 2026-08-27 복수 accountIds로 확장).
	 *
	 * @param brandSpecified 브랜드를 집어 물었는가(brandAccountId 또는 accountIds 명시) — 함의 판정용
	 * @return null = 전량(all), {@code "own"} = 경쟁사 제외, {@code "competitor"} = 경쟁사만
	 */
	static String normalizeAccountType(String raw, boolean brandSpecified) {
		if (raw == null || raw.isBlank()) {
			return brandSpecified ? null : BrandAccountType.OWN;
		}
		if (FILTER_ALL.equals(raw)) {
			return null;
		}
		if (!BrandAccountType.isValid(raw)) {
			throw V1ApiException.validation("accountType 값이 올바르지 않아요.");
		}
		return raw;
	}

	/** 미지정·{@code all}은 필터 없음(null), 그 외 값은 허용 목록 밖이면 400. */
	static String normalizeFilter(String raw, String param, String... allowed) {
		String value = normalizeFilter(raw);
		if (value == null) {
			return null;
		}
		for (String candidate : allowed) {
			if (candidate.equals(value)) {
				return value;
			}
		}
		throw V1ApiException.validation(param + " 값이 올바르지 않아요.");
	}

	/**
	 * 값 공간이 열린 파라미터(brandAccountId)용 정규화 — 미지정·빈 값·{@code all}은 필터 없음.
	 * 브랜드 id는 숫자 문자열이라 {@code all}이 실제 id와 충돌할 수 없어, FE의 "전체" 탭이 그대로
	 * 넘어와도 전량으로 받아준다.
	 */
	static String normalizeFilter(String raw) {
		return raw == null || raw.isBlank() || FILTER_ALL.equals(raw) ? null : raw;
	}

	/**
	 * 복수 브랜드 필터(2026-08-27 §2) — 쉼표 목록이다. 미지정·빈 값·{@code all}은 필터 없음(null,
	 * FE의 "전체" 탭이 그대로 넘어와도 전량), 빈 항목은 무시하고 전부 비면 필터 없음이다.
	 */
	static Set<String> normalizeAccountIds(String raw) {
		if (raw == null || raw.isBlank() || FILTER_ALL.equals(raw)) {
			return null;
		}
		Set<String> ids = Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty())
				.collect(Collectors.toCollection(LinkedHashSet::new));
		return ids.isEmpty() ? null : ids;
	}

	/**
	 * 작성자 필터(2026-08-27 §2) — 미지정·빈 값은 필터 없음. 공용 {@link #normalizeFilter(String)}을
	 * 쓰지 않는 이유는 값 공간이 인스타그램 핸들이라 {@code all}이 실제 계정명과 충돌할 수 있어서다
	 * (핸들 {@code all}을 가진 작성자를 조회할 수 없게 만들지 않는다). 비교는 대소문자 무시다 —
	 * 레거시·브랜드 풀 모두 handle은 산지에서 소문자로 정규화돼 나오지만(집계기 규칙 9의
	 * "재정규화 금지"가 서 있는 근거다), 입력은 사용자가 손으로 치는 값이라 대문자가 섞여 온다.
	 */
	static String normalizeAuthorUsername(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		return raw.trim();
	}

	static LocalDate parseDate(String raw, String param) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(raw);
		} catch (DateTimeParseException e) {
			throw V1ApiException.validation(param + "은 YYYY-MM-DD 형식이어야 해요.");
		}
	}

	// ---------- 페이지 ----------

	/**
	 * 페이지 파라미터 정규화(2026-08-27 §2, 브랜드 목록 PR #602 관용구) — 둘 다 생략이면 null(전량,
	 * 하위 호환). 하나라도 있으면 페이지 모드이고 나머지는 기본값(offset 0 · limit 100)이다.
	 */
	static PageParams normalizePage(Integer limit, Integer offset) {
		if (limit == null && offset == null) {
			return null;
		}
		int lim = limit == null ? PAGE_LIMIT_DEFAULT : limit;
		if (lim < 1 || lim > PAGE_LIMIT_MAX) {
			throw V1ApiException.validation("limit은 1~" + PAGE_LIMIT_MAX + " 사이여야 해요.");
		}
		int off = offset == null ? 0 : offset;
		if (off < 0) {
			throw V1ApiException.validation("offset은 0 이상이어야 해요.");
		}
		return new PageParams(off, lim);
	}

	/** 정규화된 페이지 파라미터 — null이면 전량 모드다({@link #normalizePage} 참조). */
	record PageParams(int offset, int limit) {
	}

	/**
	 * meta의 {@code page} 서브맵 — meta.limit(형태 호환용 필드)과 분리한 additive 필드다. 전량
	 * 응답이면 {@code {offset: 0, limit: null}}(limit null = 안 잘랐다는 표식, 키는 유지 — 계약
	 * 무결성 규칙 #1).
	 */
	static Map<String, Object> pageMeta(PageParams page) {
		Map<String, Object> pageMeta = new LinkedHashMap<>();
		pageMeta.put("offset", page == null ? 0 : page.offset());
		pageMeta.put("limit", page == null ? null : page.limit());
		return pageMeta;
	}
}
