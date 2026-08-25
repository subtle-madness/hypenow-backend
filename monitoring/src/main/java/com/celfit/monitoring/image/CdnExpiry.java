package com.celfit.monitoring.image;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 인스타 CDN 서명 만료(URL의 {@code oe} 파라미터, hex unix 초) 판정 — analytics {@code
 * ImageArchiveJob.expiryEpoch}·만료 임박순 정렬 관용구의 복제다. 모듈 간 import는 금지고 계약
 * 모듈은 분석 결과 record 전용이라({@code ARCHITECTURE §4-4}), {@link ImageDownloader}·
 * {@link ImageStore} 복제와 같은 근거로 여기 둔다. 원본과 다른 점 둘(08-17 리뷰 반영): null 가드와
 * 개연성 하한 — analytics 쪽 역이식은 후속.
 *
 * <p><b>왜 필요한가</b>(08-17 운영 실측): 아카이브 잡들은 후보를 DB 임의 순서로 순회하며 배치 상한을
 * 다운로드 시도로 소모했다. 인스타 CDN 서명은 ~4일이면 죽는데 만료 URL은 재시도해도 영원히 403이라,
 * 브랜드 게시물 썸네일 잡은 상한 1,000건 중 <b>723건을 이미 죽은 URL에 태우고</b> 아카이브는 277건만
 * 전진했다(잔여 16,529건 이월). 실패분은 {@code image_object_path}가 null로 남아 매 스윕 다시 후보가
 * 되므로 백로그가 줄지 않는다. 만료분을 시도 전에 걸러내고 남은 예산을 만료 임박 순으로 쓰면
 * 상한만큼 확실히 전진한다.
 *
 * <p><b>판정의 비대칭</b>: 만료 미상(oe 없음·파싱 불가·개연성 하한 미만)은 만료로 취급하지 않는다 —
 * 오탐(살아있는 URL을 만료로 오판)은 그 행을 영구히 버리는 반면, 미탐은 그 건이 한 번 실패할
 * 뿐이다. 하한이 필요한 실증: 자리표시자 {@code oe=abc}가 hex 2748(1970년)로 파싱돼 "확정 만료"가
 * 된 전례(이 필터 도입 커밋의 테스트 픽스처 파손) — 파싱은 되지만 터무니없는 값은 서명이 아니라
 * 쓰레기이므로 미상으로 강등해 시도를 유지한다.
 */
final class CdnExpiry {

	/** {@code oe}는 hex unix 초. 15자리 상한은 {@code Long.parseLong} 오버플로 방지용. */
	private static final Pattern OE_PARAM = Pattern.compile("[?&]oe=([0-9A-Fa-f]{1,15})(?:&|$)");

	/** 개연성 하한 = 2015-01-01T00:00:00Z — 이보다 이른 "만료"는 서명일 수 없다(IG CDN 서명 도입 이전). */
	static final long MIN_PLAUSIBLE_EPOCH = 1_420_070_400L;

	private CdnExpiry() {
	}

	/**
	 * 후보 + 미리 계산한 만료 시각. 정렬 키와 만료 제외 판정이 <b>같은 계산 결과</b>를 읽게 하는
	 * 장치다 — 판정을 URL에서 다시 파싱하면 계산 지점이 둘이 돼, 한쪽만 고친 변경(유예 기간·미상
	 * 정책)이 정렬과 필터를 조용히 어긋나게 한다.
	 */
	record Ranked<T>(T item, Long oe) {

		/** 이미 만료됐는가 — 만료 미상(oe null)은 false로 시도를 유지한다(클래스 주석의 비대칭). */
		boolean expired(long nowEpoch) {
			return oe != null && oe <= nowEpoch;
		}
	}

	/** 서명 만료 시각(unix 초) — oe가 없거나, 파싱 불가거나, 개연성 하한 미만이면 null(만료 미상). */
	static Long expiryEpoch(String url) {
		if (url == null) {
			return null;
		}
		Matcher m = OE_PARAM.matcher(url);
		if (!m.find()) {
			return null;
		}
		try {
			long epoch = Long.parseLong(m.group(1), 16);
			return epoch < MIN_PLAUSIBLE_EPOCH ? null : epoch;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * 만료 임박 순 정렬 — "먼저 죽을 URL"부터 처리한다(08-25 배치 상한 완전 제거로 지금은 전 후보가
	 * 매 스윕에서 처리되지만, 스윕이 외부 요인(배포 재시작 등)으로 중도 종료되는 경우를 대비한 순서
	 * 방어선으로 남긴다 — 임박분을 이월하면 다음 스윕엔 이미 만료돼 영구 유실). 만료 미상(oe 없음)은
	 * 맨 뒤 — 지금 안 잡아도 다음 스윕에 그대로 남아 있을 가능성이 높으므로 임박한 것에 양보한다.
	 * 만료 시각은 여기서 한 번만 계산해 {@link Ranked}에 실어 반환한다 — 호출부는 {@code
	 * r.expired(now)}로 판정하고 URL을 재파싱하지 않는다.
	 */
	static <T> List<Ranked<T>> soonestExpiryFirst(List<T> candidates, Function<T, String> urlOf) {
		return candidates.stream()
				.map(c -> new Ranked<>(c, expiryEpoch(urlOf.apply(c))))
				.sorted(Comparator.comparing(Ranked::oe,
						Comparator.nullsLast(Comparator.naturalOrder())))
				.toList();
	}
}
