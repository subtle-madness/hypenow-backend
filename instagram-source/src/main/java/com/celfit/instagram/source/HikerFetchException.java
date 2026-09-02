package com.celfit.instagram.source;

/** 그 외 수집 실패(HTTP 5xx·타임아웃·응답 셰이프 이상) — 재시도 여지가 있는 일반 실패. */
public class HikerFetchException extends RuntimeException {

	/** HTTP 상태코드 — HTTP 교환 없이 실패한 경우(IO·타임아웃·키 미설정 등) null. 지표 outcome 분류용. */
	private final Integer statusCode;

	public HikerFetchException(String message) {
		this(message, (Integer) null);
	}

	public HikerFetchException(String message, Integer statusCode) {
		super(message);
		this.statusCode = statusCode;
	}

	public HikerFetchException(String message, Throwable cause) {
		super(message, cause);
		this.statusCode = null;
	}

	public Integer statusCode() {
		return statusCode;
	}
}
