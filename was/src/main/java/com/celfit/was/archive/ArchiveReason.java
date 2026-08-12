package com.celfit.was.archive;

/** 아카이브 이관 사유 — archive.archived_rows.archived_reason에 name()이 그대로 들어간다. */
public enum ArchiveReason {
	/** 회원 탈퇴 */
	ACCOUNT_DELETION,
	/** 저장 개별 해제 */
	SAVED_REMOVED,
	/** 캠페인 삭제 */
	CAMPAIGN_DELETED,
	/** 모니터링 등록 실패 롤백 */
	REGISTRATION_ROLLBACK,
	/** 어드민 업데이트 소식 삭제 */
	NOTICE_DELETED
}
