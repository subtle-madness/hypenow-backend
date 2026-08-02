package com.celfit.was.v1.monitoring;

import com.celfit.was.monitoring.RegistrationEntryRow;
import com.celfit.was.monitoring.RegistrationResult;
import com.celfit.was.monitoring.RegistrationRow;
import com.celfit.was.v1.common.KstTimestamps;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 등록 처리 내역 응답(스펙 6.28) — 요청 1건 + 건별 처리 결과. entries는 리포지토리가 이미
 * seq ASC(입력 순서)로 정렬해 조립해 두므로 여기서는 그대로 매핑만 한다.
 *
 * <p>nullable 필드(completedAt, acknowledgedAt, entries[].reasonCode·reason·resolvedUrl·itemId)는
 * 계약 무결성 규칙 #1(1.8)에 따라 키를 생략하지 않고 명시적 null로 직렬화한다(record 기본 동작 —
 * NON_NULL 미적용). acknowledgedAt은 프론트가 localStorage로 임시 처리하던 확인(읽음) 상태를
 * 서버로 옮긴 필드 — POST /v1/monitoring/registrations/read가 채운다.
 */
public record RegistrationResponse(String id, String requestedAt, String completedAt, String acknowledgedAt,
		List<Entry> entries) {

	public record Entry(String input, String kind,
			// result 노출값은 4종(success/failed/duplicate/pending)만 — DB엔 canceled까지 5종이 있지만
			// API에서는 from()이 canceled를 failed로 접어서 내보낸다(계약을 넓히지 않기로 한 결정, 아래 주석 참조).
			@Schema(allowableValues = {RegistrationResult.SUCCESS, RegistrationResult.FAILED,
					RegistrationResult.DUPLICATE, RegistrationResult.PENDING})
			String result,
			// 값 7종 — 전부 RegistrationResult(상수 홀더, 트랙 LL)가 정본이다. 이전엔 invalid_format만
			// MonitoringInput의 공개 상수로 참조 가능하고 나머지는 executor의 비공개 상수라 문자열
			// 리터럴을 직접 박아 뒀는데, 홀더가 생기면서 전부 컴파일 상수 참조로 정리됐다. reasonCode는
			// result와 달리 canceled를 그대로 노출한다(아래 from() 주석 참조) — 접으면 운영 지표에서
			// 취소가 시스템 오류(internal_error)로 오독된다.
			@Schema(allowableValues = {RegistrationResult.REASON_INVALID_FORMAT, RegistrationResult.REASON_NOT_FOUND,
					RegistrationResult.REASON_PRIVATE_ACCOUNT, RegistrationResult.REASON_SHARE_LINK_UNRESOLVED,
					RegistrationResult.REASON_DUPLICATE, RegistrationResult.REASON_INTERNAL_ERROR,
					RegistrationResult.REASON_CANCELED})
			String reasonCode, String reason,
			String resolvedUrl, String itemId) {

		/**
		 * DB → API 매핑 지점(트랙 LL 계약 노출 방식 결정, 2026-07-31). DB에는 취소된 entry를
		 * {@code result = 'canceled'}로 그대로 저장해 두지만(원본 충실도 유지 — 운영/분석 질의는 취소를
		 * success/failed와 구분해 셀 수 있어야 한다), API 응답에서는 여기서 {@code failed}로 접어 내보낸다.
		 *
		 * <p>취소는 유저 본인의 행위라 원칙적으로는 별도 표시 어휘가 있어야 정확하지만, 이미 item 쪽
		 * status가 취소를 not_uploaded로 접어 보여주고 있어(기존 계약) registration 쪽만 canceled를
		 * 새로 노출해 프론트 계약을 넓히지 않기로 했다. reasonCode는 canceled를 그대로 내보낸다 —
		 * result만 failed로 접고 reasonCode에 원인을 남겨 두면 프론트는 "실패했고 사유는 취소"로 읽을 수
		 * 있고, 운영 지표도 reasonCode로 취소를 구분할 수 있다.
		 *
		 * <p>DB에 원본(canceled)을 남겨 두는 이유: 나중에 노출 정책이 바뀌어도(예: result에 canceled를
		 * 새로 노출) 이 매핑만 걷어내면 되고, 소급 마이그레이션이 필요 없다.
		 */
		public static Entry from(RegistrationEntryRow row) {
			String result = RegistrationResult.CANCELED.equals(row.result()) ? RegistrationResult.FAILED
					: row.result();
			return new Entry(row.input(), row.kind(), result, row.reasonCode(), row.reason(),
					row.resolvedUrl(), row.itemId() == null ? null : String.valueOf(row.itemId()));
		}
	}

	public static RegistrationResponse from(RegistrationRow row) {
		return new RegistrationResponse(
				String.valueOf(row.id()),
				KstTimestamps.toKstIso(row.requestedAt()),
				KstTimestamps.toKstIso(row.completedAt()),
				KstTimestamps.toKstIso(row.acknowledgedAt()),
				row.entries().stream().map(Entry::from).toList());
	}
}
