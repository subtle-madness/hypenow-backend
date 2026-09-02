package com.celfit.instagram.source;

/** share 단축 링크 해소 결과 — /v2/media/info/by/url 파싱(계약 §2-6). contentType은 REELS/FEED. */
public record MediaRef(String shortCode, String username, String contentType) {}
