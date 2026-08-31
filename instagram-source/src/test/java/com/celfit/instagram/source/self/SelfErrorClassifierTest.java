package com.celfit.instagram.source.self;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class SelfErrorClassifierTest {

	@Test
	void 상태코드_분류() {
		assertThat(SelfErrorClassifier.ofStatus(401, "{}")).isEqualTo(SelfErrorClass.RECOVERABLE_401);
		assertThat(SelfErrorClassifier.ofStatus(429, "")).isEqualTo(SelfErrorClass.RATE_LIMIT_429);
		assertThat(SelfErrorClassifier.ofStatus(400, "")).isEqualTo(SelfErrorClass.STRUCTURAL_400);
		assertThat(SelfErrorClassifier.ofStatus(404, "")).isEqualTo(SelfErrorClass.NOT_FOUND);
		assertThat(SelfErrorClassifier.ofStatus(403, "")).isEqualTo(SelfErrorClass.OTHER);
	}

	@Test
	void 로그인벽_HTML_은_LOGIN_WALL() {
		assertThat(SelfErrorClassifier.ofStatus(200, "<!DOCTYPE html><html>...login...</html>"))
				.isEqualTo(SelfErrorClass.LOGIN_WALL);
	}

	@Test
	void TLS_핸드셰이크_예외는_TRANSPORT() {
		assertThat(SelfErrorClassifier.ofException(new SSLHandshakeException("handshake_failure")))
				.isEqualTo(SelfErrorClass.TRANSPORT);
		assertThat(SelfErrorClassifier.ofException(new IOException("Connection reset")))
				.isEqualTo(SelfErrorClass.TRANSPORT);
	}
}
