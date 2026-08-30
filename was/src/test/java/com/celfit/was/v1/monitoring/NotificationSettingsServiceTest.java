package com.celfit.was.v1.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.IntegrationTest;
import com.celfit.was.v1.common.V1ApiException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** NotificationSettingsService — 주간 이메일 토글 1개(2026-08-27 개편 §5), 저장소 왕복 실사용. */
class NotificationSettingsServiceTest extends IntegrationTest {

	@Autowired
	NotificationSettingsService service;
	@Autowired
	JdbcClient jdbcClient;

	long userId;

	@BeforeEach
	void 유저_시드() {
		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "notif-svc-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	@Test
	void get_옵트아웃_행이_없는_유저는_기본값_true() {
		assertThat(service.get(userId).weeklyEmail()).isTrue();
	}

	@Test
	void patch_false면_옵트아웃_행이_생기고_get에도_반영된다() {
		assertThat(service.patch(userId, Map.of("weeklyEmail", false)).weeklyEmail()).isFalse();

		assertThat(service.get(userId).weeklyEmail()).isFalse();
	}

	@Test
	void patch_다시_true면_옵트아웃_행이_삭제된다() {
		service.patch(userId, Map.of("weeklyEmail", false));

		assertThat(service.patch(userId, Map.of("weeklyEmail", true)).weeklyEmail()).isTrue();
		assertThat(service.get(userId).weeklyEmail()).isTrue();
	}

	@Test
	void patch_weeklyEmail이_null이면_400() {
		Map<String, Object> body = new HashMap<>();
		body.put("weeklyEmail", null);

		assertThatThrownBy(() -> service.patch(userId, body))
				.isInstanceOf(V1ApiException.class)
				.satisfies(e -> assertThat(((V1ApiException) e).code()).isEqualTo("VALIDATION_FAILED"));
	}

	@Test
	void patch_boolean이_아니면_400() {
		assertThatThrownBy(() -> service.patch(userId, Map.of("weeklyEmail", "false")))
				.isInstanceOf(V1ApiException.class)
				.satisfies(e -> assertThat(((V1ApiException) e).code()).isEqualTo("VALIDATION_FAILED"));
	}

	@Test
	void patch_미지의_최상위_키는_400() {
		assertThatThrownBy(() -> service.patch(userId, Map.of("content", Map.of())))
				.isInstanceOf(V1ApiException.class)
				.satisfies(e -> assertThat(((V1ApiException) e).code()).isEqualTo("VALIDATION_FAILED"));
	}

	@Test
	void patch_빈_바디는_아무것도_바꾸지_않는다() {
		service.patch(userId, Map.of("weeklyEmail", false));

		assertThat(service.patch(userId, Map.of()).weeklyEmail()).isFalse();
	}
}
