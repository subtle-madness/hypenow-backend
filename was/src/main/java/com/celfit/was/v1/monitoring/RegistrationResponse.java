package com.celfit.was.v1.monitoring;

import com.celfit.was.monitoring.RegistrationEntryRow;
import com.celfit.was.monitoring.RegistrationRow;
import com.celfit.was.v1.common.KstTimestamps;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 등록 처리 내역 응답(스펙 6.28) — 요청 1건 + 건별 처리 결과. entries는 리포지토리가 이미
 * seq ASC(입력 순서)로 정렬해 조립해 두므로 여기서는 그대로 매핑만 한다.
 *
 * <p>nullable 필드(completedAt, entries[].reasonCode·reason·resolvedUrl·itemId)는 계약
 * 무결성 규칙 #1(1.8)에 따라 키를 생략하지 않고 명시적 null로 직렬화한다(record 기본 동작 —
 * NON_NULL 미적용).
 */
public record RegistrationResponse(String id, String requestedAt, String completedAt, List<Entry> entries) {

	public record Entry(String input, String kind, String result,
			// 값 6종 — invalid_format만 MonitoringInput.REASON_INVALID_FORMAT(공개 상수)로 참조 가능하고
			// 나머지 5종은 MonitoringRegistrationExecutor의 REASON_*(비공개 상수)가 정본이라 컴파일 상수로
			// 직접 참조할 수 없어 문자열 그대로 표기한다(런타임 타입은 String 그대로 유지).
			@Schema(allowableValues = {MonitoringInput.REASON_INVALID_FORMAT, "not_found", "private_account",
					"share_link_unresolved", "duplicate", "internal_error"})
			String reasonCode, String reason,
			String resolvedUrl, String itemId) {

		public static Entry from(RegistrationEntryRow row) {
			return new Entry(row.input(), row.kind(), row.result(), row.reasonCode(), row.reason(),
					row.resolvedUrl(), row.itemId() == null ? null : String.valueOf(row.itemId()));
		}
	}

	public static RegistrationResponse from(RegistrationRow row) {
		return new RegistrationResponse(
				String.valueOf(row.id()),
				KstTimestamps.toKstIso(row.requestedAt()),
				KstTimestamps.toKstIso(row.completedAt()),
				row.entries().stream().map(Entry::from).toList());
	}
}
