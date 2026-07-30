package com.celfit.was.v1.monitoring;

import com.celfit.was.monitoring.KeywordRule;
import com.celfit.was.v1.common.KstTimestamps;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * TrackingItem 응답(스펙 6.25 필드 표 전부). 이번 태스크(6.27)는 등록 직후 pending 조립
 * 정적 팩토리만 채운다(post는 항상 null — 첫 수집 전이라 TrackedPost가 없다). 목록 조회(6.26)의
 * 완전 조립(post·profileImageUrl·followers 등)은 후속 어셈블러 태스크가 이 record를 재사용한다.
 *
 * <p>nullable 필드는 계약 무결성 규칙 #1(1.8)에 따라 키를 생략하지 않고 명시적 null로
 * 직렬화한다(record 기본 동작 — NON_NULL 미적용).
 */
public record TrackingItemResponse(
		String id,
		String mode,
		String status,
		String handle,
		String displayName,
		String profileImageUrl,
		Long followers,
		String lastUploadedAt,
		String campaignId,
		String campaignName,
		String sourceUrl,
		String registeredAt,
		int trackingDays,
		Keywords keywords,
		Object post,
		String nextCheckAt) {

	/** account 모드 전용 keywords 객체(6.25) — KeywordRule.any()가 JSON의 "or" 키다. */
	public record Keywords(List<String> and, List<String> or, List<String> exclude) {

		public static Keywords from(KeywordRule rule) {
			return new Keywords(rule.and(), rule.any(), rule.exclude());
		}
	}

	/**
	 * url 모드 등록 직후 조립 — status=collecting, handle/displayName은 확인 전이라 빈 문자열,
	 * keywords·post는 null. sourceUrl은 canonical URL(post 생성 전 유일한 식별 근거, 6.25).
	 */
	public static TrackingItemResponse pendingPost(long itemId, String canonicalUrl, Long campaignId,
			String campaignName, LocalDate registeredOn, int trackingDays, OffsetDateTime nextCheckAt) {
		return new TrackingItemResponse(
				String.valueOf(itemId), "url", "collecting", "", "", null, null, null,
				campaignId == null ? null : String.valueOf(campaignId), campaignName,
				canonicalUrl, registeredOn.toString(), trackingDays, null, null,
				KstTimestamps.toKstIso(nextCheckAt));
	}

	/**
	 * account 모드 등록 직후 조립 — status=detecting, handle/displayName은 이미 정규화된 핸들값
	 * 그대로(감지 전이지만 핸들 자체는 등록 입력으로 알고 있다), keywords는 등록 조건, post는 null,
	 * sourceUrl은 account 모드라 null(6.25: 감지된 게시물은 post.url로 식별).
	 */
	public static TrackingItemResponse pendingAccount(long itemId, String handle, KeywordRule keywords,
			Long campaignId, String campaignName, LocalDate registeredOn, int trackingDays,
			OffsetDateTime nextCheckAt) {
		return new TrackingItemResponse(
				String.valueOf(itemId), "account", "detecting", handle, handle, null, null, null,
				campaignId == null ? null : String.valueOf(campaignId), campaignName,
				null, registeredOn.toString(), trackingDays, Keywords.from(keywords), null,
				KstTimestamps.toKstIso(nextCheckAt));
	}

	/**
	 * 6.29(PATCH)·6.30(cancel) 응답 조립 — target 확정 행, 또는 취소로 종결된 pending 행처럼
	 * pendingPost/pendingAccount의 고정 status(collecting/detecting)로는 표현할 수 없는 상태를
	 * 유도 헬퍼({@link ItemStatus}) 결과값으로 채운다. post·recentComments·profileImageUrl·
	 * lastUploadedAt 등의 완전 조립은 6.26 어셈블러 후속 태스크 몫이라 지금은 post=null 고정,
	 * 나머지도 현재 조회 표면에서 값을 알 수 없으면 명시적 null로 넘긴다(계약 무결성 규칙 #1).
	 */
	public static TrackingItemResponse interim(long itemId, String mode, String status, String handle,
			String displayName, String profileImageUrl, Long followers, String lastUploadedAt, Long campaignId,
			String campaignName, String sourceUrl, LocalDate registeredOn, int trackingDays, Keywords keywords,
			OffsetDateTime nextCheckAt) {
		return new TrackingItemResponse(
				String.valueOf(itemId), mode, status, handle, displayName, profileImageUrl, followers,
				lastUploadedAt, campaignId == null ? null : String.valueOf(campaignId), campaignName,
				sourceUrl, registeredOn.toString(), trackingDays, keywords, null,
				KstTimestamps.toKstIso(nextCheckAt));
	}
}
