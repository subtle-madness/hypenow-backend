package com.celfit.was.v1.monitoring;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * POST /v1/monitoring/registrations/read 요청 본문(프론트 요청) — {@code {"ids":[...]}} 또는
 * {@code {"all":true}}. 둘 다 없으면(또는 all=false만 오면) 컨트롤러가 400 VALIDATION_FAILED로
 * 거절한다. ids 원소는 계약상 문자열(id는 문자열)이며 숫자 변환 실패 원소는 컨트롤러가
 * "존재하지 않는 id"로 무시한다. 알림 읽음(6.32 NotificationReadRequest)과 동일한 형태이나
 * 도메인 분리 관용구상 별도 레코드로 둔다.
 */
public record RegistrationReadRequest(
		@Schema(description = "확인 처리할 등록 처리 내역 id 목록(문자열). 존재하지 않는 id·타 유저 id·"
				+ "이미 확인한 id는 조용히 무시한다(멱등).")
		List<String> ids,
		@Schema(description = "true면 ids와 무관하게 유저의 미확인 등록 처리 내역 전체를 확인 처리한다. "
				+ "ids와 함께 와도 all이 우선한다.")
		Boolean all) {
}
