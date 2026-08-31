package com.celfit.instagram.source.self;

import java.util.Locale;

/**
 * 상태코드·본문·전송 예외 → SelfErrorClass(스펙 §8-1). 하니스 outcomes.py의 분류 규칙 이식:
 * 전송 예외는 원인 체인을 걸어 TLS/Connect류를 잡고, 200이 HTML로 시작하면 로그인 벽으로 본다.
 */
public final class SelfErrorClassifier {

	private SelfErrorClassifier() {}

	/** 200 응답의 본문까지 보고 분류(성공/로그인벽 구분). */
	public static SelfErrorClass ofStatus(int status, String body) {
		if (status == 200) {
			String head = body == null ? "" : body.stripLeading().toLowerCase(Locale.ROOT);
			if (head.startsWith("<!doctype") || head.startsWith("<html")) {
				return SelfErrorClass.LOGIN_WALL;
			}
			return SelfErrorClass.OK;
		}
		return switch (status) {
			case 401 -> SelfErrorClass.RECOVERABLE_401;
			case 429 -> SelfErrorClass.RATE_LIMIT_429;
			case 400 -> SelfErrorClass.STRUCTURAL_400;
			case 404 -> SelfErrorClass.NOT_FOUND;
			default -> SelfErrorClass.OTHER;
		};
	}

	/** 전송 예외 분류 — 원인 체인 6홉을 걸어 TLS/Connect 키워드를 찾는다(하니스 chain-walk). */
	public static SelfErrorClass ofException(Throwable e) {
		Throwable t = e;
		for (int i = 0; i < 6 && t != null; i++) {
			String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
			if (t instanceof javax.net.ssl.SSLException
					|| msg.contains("ssl") || msg.contains("tls") || msg.contains("certificate")
					|| msg.contains("handshake") || msg.contains("buffer_underflow")) {
				return SelfErrorClass.TRANSPORT;
			}
			t = t.getCause();
		}
		// ConnectException·SocketTimeout·기타 IO는 전부 전송 실패로(재시도 대상).
		return SelfErrorClass.TRANSPORT;
	}
}
