package com.celfit.was.v1.brandmonitoring;

/**
 * 브랜드 계정 응답(FE 명세 BrandAccount) — 등록 202·목록·단건 폴링이 모두 이 셰이프를 쓴다.
 * 타임스탬프는 전부 KST 오프셋 ISO 문자열(계약 1.5), nullable은 키를 남기고 값만 null(계약 무결성 #1).
 *
 * <p>{@code collectionStatus}는 저장된 컬럼이 아니라 유도값이다(스펙 §5-2):
 * {@code collecting}(백필 진행) · {@code ready}(1회 이상 전량 수집 완주) · {@code error}(초기 백필 실패).
 *
 * <p>{@code accountType}은 brand_account가 아니라 <b>구독</b>(app.brand_monitorings)의 속성이다 —
 * 같은 브랜드라도 유저마다 own/competitor가 다를 수 있다(08-12).
 *
 * <p>{@code collectionMonths}는 자산 값 그대로 — 3개월 유저도 자산이 12면 12를 본다(스펙 결정 요약).
 */
public record BrandAccountResponse(String id, String accountType, int collectionMonths, Profile profile,
		String collectionStatus, String collectionStartedAt, String collectionCompletedAt,
		String lastDetectedAt, String lastTrackedAt, String nextScheduledAt,
		CollectionError collectionError, String createdAt) {

	/**
	 * 프로필 관측값 — 매일 스윕이 갱신한다(등록 1회 고정 아님).
	 * fullName·biography는 null 대신 ""(FE가 그대로 렌더), isVerified는 키 부재를 false로 접는다.
	 */
	public record Profile(String profileUrl, String username, String fullName, String profilePicUrl,
			boolean isVerified, Long mediaCount, Long followerCount, Long followingCount,
			String biography, String externalUrl) {
	}

	/** 수집 실패 사유 — 현재 유일한 code는 BACKFILL_FAILED(초기 백필 실패, 다음 스윕이 자동 복구). */
	public record CollectionError(String code, String message) {
	}
}
