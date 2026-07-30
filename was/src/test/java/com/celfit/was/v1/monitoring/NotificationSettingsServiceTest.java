package com.celfit.was.v1.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.IntegrationTest;
import com.celfit.was.v1.common.V1ApiException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** NotificationSettingsService — 옵트아웃 저장소 왕복(DB 실사용, 스펙 6.33). */
class NotificationSettingsServiceTest extends IntegrationTest {

	@Autowired
	NotificationSettingsService service;
	@Autowired
	JdbcClient jdbcClient;

	long userId;

	@BeforeEach
	void 유저_시드() {
		userId = jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "mon-notif-svc-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	@Test
	void get_옵트아웃_행이_없는_유저도_4종_완전체_기본값_true() {
		NotificationSettingsResponse response = service.get(userId);

		assertThat(response.content()).hasSize(4);
		assertThat(response.content().values()).allSatisfy(
				setting -> assertThat(setting.email()).isTrue());
	}

	@Test
	void patch_후_DB에_옵트아웃_행이_생기고_get에도_반영된다() {
		NotificationSettingsResponse patched = service.patch(userId,
				Map.of("content", Map.of("collection_ended", Map.of("email", false))));

		assertThat(patched.content().get("collection_ended").email()).isFalse();
		assertThat(patched.content().get("collection_started").email()).isTrue();
		assertThat(patched.content().get("metrics_private").email()).isTrue();
		assertThat(patched.content().get("content_issue").email()).isTrue();

		NotificationSettingsResponse reloaded = service.get(userId);
		assertThat(reloaded.content().get("collection_ended").email()).isFalse();
	}

	@Test
	void patch_다시_true로_보내면_옵트아웃_행이_삭제되고_get도_true로_돌아온다() {
		service.patch(userId, Map.of("content", Map.of("collection_ended", Map.of("email", false))));

		NotificationSettingsResponse restored = service.patch(userId,
				Map.of("content", Map.of("collection_ended", Map.of("email", true))));

		assertThat(restored.content().get("collection_ended").email()).isTrue();
		assertThat(service.get(userId).content().get("collection_ended").email()).isTrue();
	}

	@Test
	void patch_미지_이벤트_키는_V1ApiException_VALIDATION_FAILED() {
		assertThatThrownBy(() -> service.patch(userId,
				Map.of("content", Map.of("unknown_event", Map.of("email", false)))))
				.isInstanceOf(V1ApiException.class)
				.satisfies(e -> assertThat(((V1ApiException) e).code()).isEqualTo("VALIDATION_FAILED"));
	}

	@Test
	void patch_content_밖_최상위_키는_400() {
		assertThatThrownBy(() -> service.patch(userId, Map.of("unexpected", "value")))
				.isInstanceOf(V1ApiException.class);
	}

	@Test
	void patch_빈_바디는_아무것도_바꾸지_않는다() {
		service.patch(userId, Map.of("content", Map.of("metrics_private", Map.of("email", false))));

		NotificationSettingsResponse response = service.patch(userId, Map.of());

		assertThat(response.content().get("metrics_private").email()).isFalse();
	}
}
