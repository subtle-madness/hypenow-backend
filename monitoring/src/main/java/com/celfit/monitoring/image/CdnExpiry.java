package com.celfit.monitoring.image;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 인스타 CDN 서명 만료(URL의 {@code oe} 파라미터) 판정 — analytics {@code
 * ImageArchiveJob.expiryEpoch}·만료 임박순 정렬과 동일 관용구를 복제한 것이다. 모듈 간 import는
 * 금지고 계약 모듈은 분석 결과 record 전용이라({@code ARCHITECTURE §4-4}), {@link ImageDownloader}·
 * {@link ImageStore} 복제와 같은 근거로 여기 둔다.
 *
 * <p><b>왜 필요한가</b>(08-17 운영 실측): 아카이브 잡들은 후보를 DB 임의 순서로 순회하며 배치 상한을
 * 다운로드 시도로 소모했다. 인스타 CDN 서명은 ~4일이면 죽는데 만료 URL은 재시도해도 영원히 403이라,
 * 브랜드 게시물 썸네일 잡은 상한 1,000건 중 <b>723건을 이미 죽은 URL에 태우고</b> 아카이브는 277건만
 * 전진했다(잔여 16,529건 이월). 실패분은 {@code image_object_path}가 null로 남아 매 스윕 다시 후보가
 * 되므로 백로그가 줄지 않는다. 만료분을 시도 전에 걸러내고 남은 예산을 만료 임박 순으로 쓰면
 * 상한만큼 확실히 전진한다.
 */
final class CdnExpiry {

	/** {@code oe}는 hex unix 초. 15자리 상한은 {@code Long.parseLong} 오버플로 방지용. */
	private static final Pattern OE_PARAM = Pattern.compile("[?&]oe=([0-9A-Fa-f]{1,15})(?:&|$)");

	private CdnExpiry() {
	}

	/**
	 * 서명 만료 시각(unix 초) — {@code oe}가 없거나 파싱 불가면 {@code null}이다.
	 * null은 "만료 미상"이지 "만료됨"이 아니다 — 시도를 유지해야 한다(정상 URL을 잃지 않기 위해).
	 */
	static Long expiryEpoch(String url) {
		Matcher m = OE_PARAM.matcher(url);
		if (!m.find()) {
			return null;
		}
		try {
			return Long.parseLong(m.group(1), 16);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** 이미 만료됐는가 — 만료 미상(oe 없음·파싱 불가)은 false로 시도를 유지한다. */
	static boolean isExpired(String url, long nowEpoch) {
		Long oe = expiryEpoch(url);
		return oe != null && oe <= nowEpoch;
	}

	/**
	 * 만료 임박 순 정렬 — 배치 상한이 걸릴 때 남은 예산을 "먼저 죽을 URL"에 쓴다. 만료 미상(oe 없음)은
	 * 맨 뒤: 지금 안 잡아도 다음 스윕에 그대로 남아 있을 가능성이 높으므로 임박한 것에 양보한다.
	 *
	 * <p>정렬 키는 미리 계산해 {@link IdentityHashMap}에 담는다 — 비교마다 정규식을 다시 돌리면
	 * 후보 수만큼 O(n log n) 정규식이 되고(운영 브랜드 후보 27,000건 규모), 값이 같은 후보끼리
	 * 키가 뭉개지지 않도록 값 동등성이 아니라 객체 동일성으로 잡는다.
	 */
	static <T> List<T> soonestExpiryFirst(List<T> candidates, Function<T, String> urlOf) {
		Map<T, Long> expiry = new IdentityHashMap<>();
		for (T c : candidates) {
			expiry.put(c, expiryEpoch(urlOf.apply(c)));
		}
		return candidates.stream()
				.sorted(Comparator.comparing(expiry::get,
						Comparator.nullsLast(Comparator.naturalOrder())))
				.toList();
	}
}
