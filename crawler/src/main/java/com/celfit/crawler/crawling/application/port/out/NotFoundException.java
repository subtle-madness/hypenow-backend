package com.celfit.crawler.crawling.application.port.out;

/**
 * 대상 자체가 없다는 404 — 재시도가 무의미한 실패(계정 삭제·개명 등).
 * 일반 ApifyException(재시도 대상)과 달리 호출자가 소프트 딜리트 등 종결 처리를 한다.
 */
public class NotFoundException extends ApifyException {
    public NotFoundException(String message) {
        super(message);
    }
}
