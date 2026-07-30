package com.celfit.monitoring.alarm;

/**
 * 대장 행의 메일 발송 상태. PENDING·FAILED만 다음 틱의 발송 대상이다 —
 * SKIPPED_* 는 재시도가 무의미한 종결이고(옵트아웃·수신자 부재), SENT는 완료다.
 */
public enum AlarmEmailStatus {

	PENDING, SENT,
	/** 유저가 그 이벤트 종류의 메일을 껐다 — 대장엔 남아 앱 내 알림으로는 계속 서빙된다. */
	SKIPPED_OPTOUT,
	/** 유저 삭제·이메일 부재 — 몇 번을 더 시도해도 보낼 곳이 없다. */
	SKIPPED_NO_RECIPIENT,
	/** 발송 실패(Resend 오류·네트워크) — 다음 틱에 그 행만 다시 시도한다. */
	FAILED
}
