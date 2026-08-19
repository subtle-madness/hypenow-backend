package com.celfit.was.v1.brandmonitoring;

/**
 * 브랜드 계정 응답(FE 명세 BrandAccount) — 등록 202·목록·단건 폴링이 모두 이 셰이프를 쓴다.
 * 타임스탬프는 전부 KST 오프셋 ISO 문자열(계약 1.5), nullable은 키를 남기고 값만 null(계약 무결성 #1).
 *
 * <p>{@code collectionStatus}는 저장된 컬럼이 아니라 유도값이다(스펙 §5-2, 08-13 개정):
 * {@code collecting}(보여줄 데이터가 없는 첫 백필 진행) · {@code ready}(이번 창 기준 완주 —
 * {@code last_swept_on} 보유. 완주 이력이 없는 첫 등록·재가입·기간 확장 중은 {@code last_swept_at}이
 * 기준) · {@code error}(보여줄 데이터가 없는 상태의 백필 실패).
 * 값 공간은 이 3값 고정이다(FE 요청 계약) — 수집 진행 여부는 상태값이 아니라
 * {@code collectionCompletedAt == null}로 판정한다(확장 시 monitoring이 이 값을 리셋한다).
 * 정확한 유도 규칙은 {@link BrandAccountAssembler} javadoc이 정본이다.
 *
 * <p>{@code accountType}은 brand_account가 아니라 <b>구독</b>(app.brand_monitorings)의 속성이다 —
 * 같은 브랜드라도 유저마다 own/competitor가 다를 수 있다(08-12).
 *
 * <p>{@code collectionMonths}는 연결(유저) 레벨 신청값이다(2026-08-17) — 자산 값(유저 간 max)이
 * 아니라 이 유저가 등록 시 고른 표시 기간. 게시물 목록·counts도 같은 창으로 잘려 내려간다.
 *
 * <p>{@code collectionCapped}·{@code coveredUntil}은 실제 커버리지다(수집 상한 v2 §7-1):
 * {@code collectionCapped} = 백필이 수집 개수 상한(2,000)에서 끊겼는지,
 * {@code coveredUntil} = 실수집 깊이(이 시각 이후 구간이 수집 범위) — null이면 요청 창 전체 커버.
 * FE 표기: "N개월 신청 · YYYY-MM-DD까지 수집(상한 도달)". 자산(브랜드) 속성이라 같은 브랜드를 보는
 * 유저 전원이 같은 값을 받는다 — 신청 창({@code collectionMonths})만 유저별로 다르다.
 */
public record BrandAccountResponse(String id, String accountType, int collectionMonths, Profile profile,
		String collectionStatus, String collectionStartedAt, String collectionCompletedAt,
		String lastDetectedAt, String lastTrackedAt, String nextScheduledAt,
		CollectionError collectionError, String createdAt,
		boolean collectionCapped, String coveredUntil) {

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
