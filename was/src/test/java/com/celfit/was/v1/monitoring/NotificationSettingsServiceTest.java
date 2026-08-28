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
	void get_응답의_content_키_순서는_이벤트_4종_고정_순서다() {
		NotificationSettingsResponse response = service.get(userId);

		assertThat(response.content().keySet()).containsExactly(
				"collection_started", "collection_ended", "metrics_private", "content_issue");
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
	void patch_한_요청에_이벤트_2개_이상_동시_변경() {
		NotificationSettingsResponse patched = service.patch(userId,
				Map.of("content", Map.of(
						"collection_ended", Map.of("email", false),
						"content_issue", Map.of("email", false))));

		assertThat(patched.content().get("collection_ended").email()).isFalse();
		assertThat(patched.content().get("content_issue").email()).isFalse();
		assertThat(patched.content().get("collection_started").email()).isTrue();
		assertThat(patched.content().get("metrics_private").email()).isTrue();

		// 응답뿐 아니라 DB에도 두 이벤트 모두 옵트아웃 행이 생겼는지 재조회로 확인.
		NotificationSettingsResponse reloaded = service.get(userId);
		assertThat(reloaded.content().get("collection_ended").email()).isFalse();
		assertThat(reloaded.content().get("content_issue").email()).isFalse();
		assertThat(reloaded.content().get("collection_started").email()).isTrue();
		assertThat(reloaded.content().get("metrics_private").email()).isTrue();
	}

	@Test
	void patch_content가_null이면_400() {
		Map<String, Object> body = new HashMap<>();
		body.put("content", null);

		assertThatThrownBy(() -> service.patch(userId, body))
				.isInstanceOf(V1ApiException.class)
				.satisfies(e -> assertThat(((V1ApiException) e).code()).isEqualTo("VALIDATION_FAILED"));
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

	@Test
	void WEEKLY_DIGEST_옵트아웃_행이_있어도_get이_500_없이_4종_매트릭스를_돌려준다() {
		// 2026-08-28 재리뷰 Critical 회귀 — V20260827135725 마이그레이션이 기존 옵트아웃 유저
		// 전원에게 WEEKLY_DIGEST 행을 백필했다. 이 유저는 옵트아웃 이력이 있어(collection_ended)
		// 백필 대상이었던 것과 동형 — WEEKLY_DIGEST 행이 섞인 상태에서 GET이 여전히 200 +
		// 4종 완전체를 내려야 한다(수정 전에는 EmailOptOutRepository.findOptOuts의
		// Collectors.toUnmodifiableSet()이 NPE를 던져 500이 났다).
		service.patch(userId, Map.of("content", Map.of("collection_ended", Map.of("email", false))));
		jdbcClient.sql("""
				INSERT INTO app.monitoring_email_opt_outs (user_id, event_type) VALUES (:userId, 'WEEKLY_DIGEST')
				""")
				.param("userId", userId)
				.update();

		NotificationSettingsResponse response = service.get(userId);

		assertThat(response.content()).hasSize(4);
		assertThat(response.content().get("collection_ended").email()).isFalse();
		assertThat(response.content().get("collection_started").email()).isTrue();
		assertThat(response.content().get("metrics_private").email()).isTrue();
		assertThat(response.content().get("content_issue").email()).isTrue();
	}
}
