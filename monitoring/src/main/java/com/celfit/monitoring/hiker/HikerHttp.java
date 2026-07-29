package com.celfit.monitoring.hiker;

/** HikerAPI HTTP 전송 격리 — 테스트에서 fake로 대체. path는 base-url 이후(쿼리 포함). */
public interface HikerHttp {

	String get(String path);
}
