package com.celfit.instagram.source.self;

/** 자체크롤 실패 — errorClass가 FailoverInstagramSource의 라우팅을 결정한다. */
public class SelfCrawlException extends RuntimeException {

	private final transient SelfErrorClass errorClass;

	public SelfCrawlException(SelfErrorClass errorClass, String message) {
		super(message);
		this.errorClass = errorClass;
	}

	public SelfCrawlException(SelfErrorClass errorClass, String message, Throwable cause) {
		super(message, cause);
		this.errorClass = errorClass;
	}

	public SelfErrorClass errorClass() {
		return errorClass;
	}
}
