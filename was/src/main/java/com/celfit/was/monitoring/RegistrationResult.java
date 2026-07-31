package com.celfit.was.monitoring;

/**
 * app.monitoring_registration_entries의 result·reason_code 어휘(6.28) 상수 홀더 — 이전에는
 * {@code V1MonitoringRegistrationService}·{@code MonitoringRegistrationExecutor}가 각자 중복
 * 정의하고 있었다. 트랙 LL(등록 entry 영구 pending 정산)에서 result에 canceled가 추가되며 소비처가
 * 셋으로 늘어 한곳으로 모았다.
 *
 * <p>enum이 아니라 상수 홀더인 이유: DB(CHECK 제약)·JSON(프론트 응답) 모두 문자열 그대로 통과하는
 * 계약이고 기존 코드가 전부 String으로 흐른다(예: RegistrationResponse.Entry.from은 DB에서 읽은
 * String을 그대로 직렬화). enum 전환은 별개의 큰 리팩터링이고, 같은 위상의
 * {@code com.celfit.was.v1.monitoring.ItemStatus}도 이미 상수 홀더 패턴이다.
 */
public final class RegistrationResult {

	// result 5종
	public static final String SUCCESS = "success";
	public static final String FAILED = "failed";
	public static final String DUPLICATE = "duplicate";
	public static final String PENDING = "pending";
	public static final String CANCELED = "canceled";

	// reason_code 7종
	public static final String REASON_INVALID_FORMAT = "invalid_format";
	public static final String REASON_NOT_FOUND = "not_found";
	public static final String REASON_PRIVATE_ACCOUNT = "private_account";
	public static final String REASON_SHARE_LINK_UNRESOLVED = "share_link_unresolved";
	public static final String REASON_DUPLICATE = "duplicate";
	public static final String REASON_INTERNAL_ERROR = "internal_error";
	public static final String REASON_CANCELED = "canceled";

	private RegistrationResult() {
	}
}
