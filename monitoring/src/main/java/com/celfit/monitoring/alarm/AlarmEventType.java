package com.celfit.monitoring.alarm;

import java.util.Optional;

/**
 * 알람 이벤트 4종(스펙 §1-3) — 화면 문구와 1:1이고, app 옵트아웃 테이블의 event_type 어휘와도 같다.
 * 이 enum이 어휘의 정본이다(계약 v2 §3·§6).
 */
public enum AlarmEventType {

	/** 게시물 수집 시작 — 직접 등록(즉시 레인) 또는 스윕 첫 감지 자동 전환(아침 레인). */
	COLLECTION_STARTED,
	/** 게시물 수집 종료 — 기간 만료(EXPIRED 전이). */
	COLLECTION_ENDED,
	/** 일부 지표 비공개 — 스냅샷 지표가 값→null로 전환. */
	METRICS_HIDDEN,
	/** 콘텐츠 비공개/삭제/수집 오류 — FAILED 전이(재시도로 해소 불가한 결정적 실패). */
	CONTENT_UNAVAILABLE;

	/** 외부(app 옵트아웃 행)에서 온 문자열 해석 — 모르는 어휘는 무시한다(was가 먼저 배포될 수 있다). */
	public static Optional<AlarmEventType> parse(String value) {
		for (AlarmEventType type : values()) {
			if (type.name().equals(value)) {
				return Optional.of(type);
			}
		}
		return Optional.empty();
	}
}
