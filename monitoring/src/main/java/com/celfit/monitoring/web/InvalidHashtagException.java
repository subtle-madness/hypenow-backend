package com.celfit.monitoring.web;

/**
 * 태그 셋 요청(PUT 전체 교체·POST 추가) 거부 — 두 경우:
 * (1) 유효하지 않은 문자(IG 해시태그 불가 문자)를 포함한 태그 — 유저 입력이라 유도
 * (BrandHashtagTags.derive)와 달리 조용히 잘라내지 않고 거부한다(잘라내면 유저가 입력한 문자열과
 * 실제 저장된 태그가 달라지는데 응답만으로는 알 수 없어 "왜 감지가 안 되지" 문의로 이어진다).
 * PUT·POST 공용.
 * (2) POST(추가) 정규화 결과가 빈 목록 — "추가할 태그가 없다"는 요청 자체가 무의미해 실수일
 * 확률이 높다. PUT은 2026-08-12부터 빈 목록을 허용한다(전체 삭제 API가 생겨 "전부 비우기"가
 * 정당한 상태가 됐다 — 구 하한 가드는 폐지됐다) — 이 경우는 POST 전용이다.
 * 형식은 유효하지만 비즈니스 규칙 위반이라 400이 아니라 422(ApiExceptionHandler 매핑).
 */
public class InvalidHashtagException extends RuntimeException {

	public InvalidHashtagException(String message) {
		super(message);
	}
}
