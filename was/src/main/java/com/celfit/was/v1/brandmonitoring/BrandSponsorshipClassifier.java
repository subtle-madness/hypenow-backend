package com.celfit.was.v1.brandmonitoring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 협찬 판정(FE §4.4) — 조회 시 계산·저장 없음(캡션 원문이 있어 키워드 개선이 과거분에 즉시 소급).
 *
 * <p>마커는 전부 <b>확정형만</b> 담는다(정밀도 우선) — 애매한 표현을 넣어 오탐이 나면 브랜드 화면에서
 * 오가닉 성과가 협찬으로 집계된다. 마커 형태는 세 갈래로 나뉜다:
 * <ul>
 * <li>부분 문자열(한국어·CJK) — 다른 단어에 안 섞이는 표현이라 contains로 충분.</li>
 * <li>라틴 해시태그 — 반드시 태그 토큰 전체 일치. substring이면 {@code #adventure}가
 *     {@code #ad}에 걸린다(08-07 운영 실측: 해외 인플루언서는 #ad류 표기만 씀).</li>
 * <li>단어 접두(터키어 reklam) — 교착어 접미사(reklamdır 등)가 붙어 태그 밖에서도 쓰인다.</li>
 * <li>캡션 선두 접두 표기("광고 ㅣ …") — 첫 토큰이 광고/협찬/ad이고 바로 구분자가 따라올 때만.
 *     구분자는 파이프 대용 문자들(한글 모음 ㅣ U+3163, 소문자 l — 08-10 운영 실측 DbU1UKMR7Nk·
 *     DbSa8zcBJCn)을 포함하며, 구분자 없이 단어가 이어지면("광고 아님", "adorable") 불인정.</li>
 * </ul>
 */
public final class BrandSponsorshipClassifier {

	/** 판정 값 공간 — 소비처(어셈블러·필터·counts)가 리터럴을 다시 적지 않게 상수로 노출한다. */
	public static final String SPONSORED = "sponsored";
	public static final String ORGANIC = "organic";
	public static final String UNKNOWN = "unknown";

	private static final List<String> CONFIRM_SUBSTRINGS = List.of(
			// 한국어
			"#광고", "#협찬", "#유료광고", "유료 광고", "유료광고", "광고입니다",
			"협찬받", "협찬 받", "제품제공", "제품 제공", "제공받아",
			// CJK — 広告(일)·广告(중 간체)·業配(대만 협찬 콘텐츠 관용어)
			"広告", "广告", "業配");

	/** 라틴 해시태그는 소문자 태그 토큰 전체 일치만 인정한다. */
	private static final Set<String> CONFIRM_HASHTAGS = Set.of(
			"ad", "sponsored", "sponsor", "gifted", "pr", "prsample", "paidpartnership",
			// publicidad(서)·anzeige·werbung(독)
			"publicidad", "anzeige", "werbung");

	private static final Pattern HASHTAG = Pattern.compile("#([\\p{L}\\p{N}_]+)");

	/** 앞이 문자·숫자가 아니면 매치 — "*reklam", "reklamdır"를 잡고 단어 중간 매치는 막는다. */
	private static final Pattern WORD_PREFIX_MARKERS = Pattern.compile("(?<![\\p{L}\\p{N}])reklam");

	/**
	 * 캡션 선두의 "광고 ㅣ …"류 접두 표기 — 선두 앵커 + 구분자 필수라 "광고 아님"·"adorable"에는
	 * 안 걸린다. 소문자 l은 파이프 대용으로만 쓰여서 단독 토큰(뒤가 공백)일 때만 구분자로 인정.
	 */
	private static final Pattern LEADING_PREFIX_DISCLOSURE =
			Pattern.compile("^\\s*(?:광고|협찬|ad)(?:\\s*[ㅣ|｜/\\-–—:,.·~]|\\s+l(?=\\s))");

	private BrandSponsorshipClassifier() {}

	public static String classify(Boolean isPaidPartnership, String caption) {
		return classify(isPaidPartnership, caption != null && containsSponsorshipMarker(caption));
	}

	/**
	 * (isPaidPartnership, 캡션 마커 매치 여부) → 판정. SQL이 마커 매치를 대신 계산한 경로
	 * (슬림 인덱스, 2026-08-27 P0)용 — 판정 트리는 caption 버전과 동일하다.
	 */
	public static String classify(Boolean isPaidPartnership, boolean captionMarker) {
		if (Boolean.TRUE.equals(isPaidPartnership)) {
			return SPONSORED;
		}
		if (captionMarker) {
			return SPONSORED;
		}
		return Boolean.FALSE.equals(isPaidPartnership) ? ORGANIC : UNKNOWN;
	}

	/**
	 * Java \s(ASCII 공백 6종)와 <b>같은</b> 문자만 — PG [[:space:]]는 로케일에 따라 유니코드 공백까지
	 * 넓어진다. \013은 수직탭 U+000B로, Java \s에는 들어가고 \t\n\f\r에는 안 잡혀 빠뜨리기 쉽다
	 * (빠지면 "광고<VT>ㅣ …" 캡션에서 SQL만 미탐).
	 */
	private static final String ARE_SPACE = "[ \t\n\013\f\r]";

	/**
	 * 마커 상수 → Postgres ARE 정규식(2026-08-27 P0) — {@code lower(caption) ~ :regex}로 쓴다.
	 * Java 정규식과의 문법 차이(룩비하인드·룩어헤드 없음)는 소비형으로 등가 변환한다:
	 * {@code (?<![\p{L}\p{N}])} → {@code (^|[^[:alnum:]])}, {@code l(?=\s)} → {@code l[공백]}.
	 * 동치성은 SQL 골든 코퍼스 테스트(BrandSponsorshipSqlEquivalenceTest)가 봉인한다 —
	 * 마커 상수를 고치면 그 테스트가 함께 검증한다(별도 갱신 불필요, 코퍼스에 사례만 추가).
	 *
	 * <p><b>전제</b>: {@code [[:alnum:]]}의 유니코드 인식 여부는 DB 이미지·로케일에 종속된다 —
	 * 테스트 컨테이너와 운영이 같은 {@code postgres:17-alpine}이라 동치가 성립한다.
	 * 이미지 계열을 바꿨을 때 동치성 테스트가 깨지면 이 전제부터 확인할 것.
	 *
	 * <p>상수에서만 파생되는 불변값이라 클래스 로드 시 1회 빌드해 캐시한다 — 브랜드 목록·대시보드가
	 * 요청·계정마다 부르는 자리라 재조립 비용을 반복하지 않는다.
	 */
	public static String postgresMarkerRegex() {
		return POSTGRES_MARKER_REGEX;
	}

	private static final String POSTGRES_MARKER_REGEX = buildPostgresMarkerRegex();

	private static String buildPostgresMarkerRegex() {
		List<String> alts = new ArrayList<>();
		for (String marker : CONFIRM_SUBSTRINGS) {
			alts.add(escapeAre(marker));
		}
		// 해시태그: # 뒤 태그 토큰 전체 일치 — 뒤가 토큰 문자면 다른 태그(#adventure ≠ #ad).
		// ARE는 최장 일치라 순서 무관하지만 결정성 위해 길이 내림차순 정렬.
		String tags = CONFIRM_HASHTAGS.stream()
				.sorted(Comparator.comparingInt(String::length).reversed()
						.thenComparing(Comparator.naturalOrder()))
				.map(BrandSponsorshipClassifier::escapeAre)
				.collect(Collectors.joining("|"));
		alts.add("#(?:" + tags + ")($|[^[:alnum:]_])");
		// reklam 단어 접두 — 앞이 문자·숫자면 단어 중간(WORD_PREFIX_MARKERS 등가).
		alts.add("(^|[^[:alnum:]])reklam");
		// 캡션 선두 접두 표기(LEADING_PREFIX_DISCLOSURE 등가) — 하이픈은 클래스 마지막에 둔다(ARE).
		alts.add("^" + ARE_SPACE + "*(?:광고|협찬|ad)(?:" + ARE_SPACE + "*[ㅣ|｜/–—:,.·~-]|"
				+ ARE_SPACE + "+l" + ARE_SPACE + ")");
		return alts.stream().map(a -> "(?:" + a + ")").collect(Collectors.joining("|"));
	}

	/** ARE 메타문자 이스케이프 — 마커 리터럴이 정규식으로 오작동하지 않게. */
	private static String escapeAre(String literal) {
		return literal.replaceAll("([\\\\.^$*+?()\\[\\]{}|])", "\\\\$1");
	}

	static boolean containsSponsorshipMarker(String caption) {
		String lower = caption.toLowerCase(Locale.ROOT);
		for (String marker : CONFIRM_SUBSTRINGS) {
			if (lower.contains(marker)) {
				return true;
			}
		}
		if (WORD_PREFIX_MARKERS.matcher(lower).find()) {
			return true;
		}
		if (LEADING_PREFIX_DISCLOSURE.matcher(lower).find()) {
			return true;
		}
		Matcher tags = HASHTAG.matcher(lower);
		while (tags.find()) {
			if (CONFIRM_HASHTAGS.contains(tags.group(1))) {
				return true;
			}
		}
		return false;
	}
}
