package com.celfit.instagram.source.self;

/**
 * 자체크롤 실패 분류 — FailoverInstagramSource의 라우팅 결정에 쓴다(스펙 §8-1).
 */
public enum SelfErrorClass {
	/** 200 + 파싱 성공(예외 아님, 분류 완결성용). */
	OK,
	/** 익명 한도 401 — 로테이트+재시도로 회복 가능. */
	RECOVERABLE_401,
	/** 429 봇판정/과열 — 재시도(새 터널). */
	RATE_LIMIT_429,
	/** 전송 실패(TLS/Connect/Proxy/Protocol/Timeout) — geo:kr + 새 터널 1회 재시도. */
	TRANSPORT,
	/** 200인데 로그인 벽 HTML — 이 표면 소진, 다음 표면/Hiker. */
	LOGIN_WALL,
	/** 구조적 400(계정 버그) — 재시도 무의미, 즉시 Hiker. */
	STRUCTURAL_400,
	/** 404 — 계정/게시물 부재. 종료(스킵), 폴백 안 함. */
	NOT_FOUND,
	/** 403·기타 — Hiker 폴백. */
	OTHER
}
