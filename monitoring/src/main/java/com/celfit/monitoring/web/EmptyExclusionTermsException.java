package com.celfit.monitoring.web;

/**
 * 제외 문자열 전체 교체 요청이 정규화 후 빈 목록이면 거부 — 판정은 비소급(이미 저장된 verdict
 * 불변)이라, 빈 목록으로 전부 지우면 자사 게시물(스트림의 71~87%)이 RELEVANT로 저장된 뒤 term을
 * 되돌려도 복구 불가하다. 빈 목록은 실수일 확률이 압도적이라 하한 가드(최소 1개)를 둔다.
 * 형식은 유효하지만 비즈니스 규칙 위반이라 400이 아니라 422(ApiExceptionHandler 매핑).
 */
public class EmptyExclusionTermsException extends RuntimeException {

	public EmptyExclusionTermsException(String message) {
		super(message);
	}
}
