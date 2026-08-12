package com.celfit.monitoring.web;

/**
 * 태그 셋 전체 교체 요청 거부(EmptyExclusionTermsException 관용구 동형) — 두 경우:
 * (1) 유효하지 않은 문자(IG 해시태그 불가 문자)를 포함한 태그 — 유저 입력이라 유도
 * (BrandHashtagTags.derive)와 달리 조용히 잘라내지 않고 거부한다(잘라내면 유저가 입력한 문자열과
 * 실제 저장된 태그가 달라지는데 응답만으로는 알 수 없어 "왜 감지가 안 되지" 문의로 이어진다).
 * (2) 정규화 결과가 빈 목록 — 감지가 조용히 꺼지는 것을 방지(제외 문자열 빈 목록 거부와 같은 근거).
 * 형식은 유효하지만 비즈니스 규칙 위반이라 400이 아니라 422(ApiExceptionHandler 매핑).
 */
public class InvalidHashtagException extends RuntimeException {

	public InvalidHashtagException(String message) {
		super(message);
	}
}
