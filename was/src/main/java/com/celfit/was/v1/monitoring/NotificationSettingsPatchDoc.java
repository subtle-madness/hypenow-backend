package com.celfit.was.v1.monitoring;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * PATCH /v1/notification-settings의 Swagger 문서 전용 스키마(2026-08-27 주간 개편 §5) —
 * <b>런타임 역직렬화에는 쓰지 않는다.</b> 실제 컨트롤러 파라미터 타입은 여전히
 * {@code Map<String,Object>}이며(V1NotificationSettingsController 참조), 이 레코드는
 * {@code @RequestBody(content=@Content(schema=@Schema(implementation=...)))}로 springdoc이
 * 뽑는 필드 스키마만 대체한다.
 *
 * <p>필드 정의의 정본은 {@link NotificationSettingsService#patch}의 검증 분기다. 이벤트 종류별
 * 4토글 매트릭스(구 {@code content} 맵)는 폐지됐고 주간 이메일 수신 토글 하나만 남았다.
 */
public final class NotificationSettingsPatchDoc {

	private NotificationSettingsPatchDoc() {
	}

	@Schema(name = "NotificationSettingsPatchRequest", description = "주간 리포트 메일 수신 여부만 바꾼다. "
			+ "키를 생략하면 아무것도 바뀌지 않는다. weeklyEmail 밖의 최상위 키, boolean이 아닌 값은 "
			+ "전부 400 VALIDATION_FAILED.")
	public record Request(
			@Schema(description = "주간 리포트 메일 수신 여부. false면 인앱 알림만 받는다.")
			Boolean weeklyEmail) {
	}
}
