package com.celfit.instagram.source.self;

/** 저수준 전송 응답 — 상태코드 + 본문(gunzip 완료). 401은 전송이 복원해 넣는다. */
public record SelfResponse(int status, String body) {}
