package com.celfit.monitoring.hiker;

/** 그 외 수집 실패(HTTP 5xx·타임아웃·응답 셰이프 이상) — 재시도 여지가 있는 일반 실패. */
public class HikerFetchException extends RuntimeException {

	public HikerFetchException(String message) {
		super(message);
	}

	public HikerFetchException(String message, Throwable cause) {
		super(message, cause);
	}
}
