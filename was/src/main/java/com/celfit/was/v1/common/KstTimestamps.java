package com.celfit.was.v1.common;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * KST(Asia/Seoul) 오프셋 직렬화 공용 유틸(스펙 1.5) — 타임스탬프 필드(createdAt·nextCheckAt·requestedAt 등)는
 * UTC(Z)가 아니라 KST 오프셋(`2026-07-29T09:00:00+09:00`)으로 내려야 프론트가 벽시계 값을 그대로 표시한다.
 * 순수 정적 유틸(부작용 없음). 모니터링 응답 DTO(캠페인·TrackingItem·Registration·Digest 등)가 공통으로 쓴다.
 *
 * <p>기존 어셈블러 4곳(V1InfluencerReportAssembler 등)의 private KST 상수는 날짜 전용(LocalDate) 파생이
 * 목적이라 이 유틸의 범위 밖이다 — 건드리지 않는다.
 */
public final class KstTimestamps {

	public static final ZoneId KST = ZoneId.of("Asia/Seoul");

	// OffsetDateTime.toString()은 초가 0이면 생략해(`09:00+09:00`) 스펙 예시(`09:00:00+09:00`)와
	// 어긋나므로 초를 항상 명시하는 포맷터를 쓴다.
	private static final DateTimeFormatter KST_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

	private KstTimestamps() {
	}

	/** KST 오프셋 ISO 8601 문자열로 변환. null이면 null(계약 무결성 규칙 #1 — 키 생략 대신 명시적 null). */
	public static String toKstIso(OffsetDateTime at) {
		return at == null ? null : KST_ISO.format(at.atZoneSameInstant(KST));
	}

	/** KST 기준 달력 날짜(`YYYY-MM-DD`)로 변환. null이면 null. */
	public static LocalDate toKstDate(OffsetDateTime at) {
		return at == null ? null : at.atZoneSameInstant(KST).toLocalDate();
	}
}
