package com.celfit.was.monitoring;

/** 공유 단축 링크 해소 응답(계약 §2-6). contentType은 monitoring 어휘(REELS/FEED 등) 그대로 전달. */
public record ShareResolveResult(String shortCode, String username, String contentType) {
}
