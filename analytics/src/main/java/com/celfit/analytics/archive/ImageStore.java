package com.celfit.analytics.archive;

/** 이미지 바이트를 오브젝트 스토리지에 넣는 포트 — 테스트는 fake로 대체. */
public interface ImageStore {

	void put(String objectPath, byte[] bytes, String contentType, String cacheControl);
}
