package com.celfit.was.v1.monitoring;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * PATCH /v1/notification-settings의 Swagger 문서 전용 스키마(계약 6.33) — <b>런타임 역직렬화에는
 * 쓰지 않는다.</b> 실제 컨트롤러 파라미터 타입은 여전히 {@code Map<String,Object>}이며
 * (V1NotificationSettingsController 참조), 이 레코드는 {@code @RequestBody(content=@Content(
 * schema=@Schema(implementation=...)))}로 springdoc이 뽑는 필드 스키마만 대체한다.
 *
 * <p>필드 정의의 정본은 {@link NotificationSettingsService#patch}의 검증 분기다. content는 이벤트
 * 유형(문자열 키) → 채널 설정의 <b>부분</b> 맵이라 고정 필드 레코드 대신 Map으로 표현한다 — 유효 키 4종
 * (collection_started, collection_ended, metrics_private, content_issue)은 {@link MonitoringEventTypes}가
 * 정본이며, 응답 스키마({@link NotificationSettingsResponse})는 이미 완전체 4키로 노출돼 있다.
 */
public final class NotificationSettingsPatchDoc {

	private NotificationSettingsPatchDoc() {
	}

	@Schema(name = "NotificationSettingsPatchRequest", description = "부분 갱신 — 명시한 이벤트 키만 갱신되고 "
			+ "나머지는 기존 값을 유지한다. 유효 이벤트 키 4종: collection_started, collection_ended, "
			+ "metrics_private, content_issue(정본: MonitoringEventTypes.EVENT_TYPES). content 밖의 키, "
			+ "미지 이벤트 키, email이 아닌 채널 키, boolean이 아닌 값은 전부 400 VALIDATION_FAILED.")
	public record Request(
			@Schema(description = "이벤트 유형(키, 위 4종 중 일부) → 채널 설정의 부분 맵. "
					+ "예: {\"collection_ended\": {\"email\": false}}")
			Map<String, EventSetting> content) {
	}

	public record EventSetting(
			@Schema(description = "이메일 발송 여부.", requiredMode = Schema.RequiredMode.REQUIRED)
			Boolean email) {
	}
}
