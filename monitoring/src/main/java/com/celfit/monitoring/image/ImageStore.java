package com.celfit.monitoring.image;

/**
 * 이미지 바이트를 오브젝트 스토리지에 넣는 포트 — analytics
 * {@code com.celfit.analytics.archive.ImageStore}를 그대로 복제(모듈 간 import 금지, 클래스
 * 주석 참고). 테스트는 fake로 대체한다.
 */
public interface ImageStore {

	void put(String objectPath, byte[] bytes, String contentType, String cacheControl);
}
