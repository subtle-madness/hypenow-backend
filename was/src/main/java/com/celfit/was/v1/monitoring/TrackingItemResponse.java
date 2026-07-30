package com.celfit.was.v1.monitoring;

import com.celfit.was.monitoring.KeywordRule;
import com.celfit.was.v1.common.KstTimestamps;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * TrackingItem 응답(스펙 6.25 필드 표 전부). 등록 직후(6.27)는 pending 조립 팩토리(post는 항상
 * null — 첫 수집 전이라 TrackedPost가 없다)를, 목록 조회(6.26)·행 수정(6.29)·취소(6.30)는
 * {@link #full} 팩토리를 쓴다(완전 조립은 {@code TrackingItemAssembler} 몫).
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
		TrackedPostResponse post,
		String nextCheckAt) {

	/** account 모드 전용 keywords 객체(6.25) — KeywordRule.any()가 JSON의 "or" 키다. */
	public record Keywords(List<String> and, List<String> or, List<String> exclude) {

		public static Keywords from(KeywordRule rule) {
			return new Keywords(rule.and(), rule.any(), rule.exclude());
		}
	}

	/** TrackedPost(6.25) — target 확정 & tracked_short_code가 있는 행에서만 값을 가진다. */
	public record TrackedPostResponse(
			String url,
			String contentType,
			String uploadedAt,
			String caption,
			List<String> matchedKeywords,
			String thumbnailUrl,
			String hiddenAt,
			List<SnapshotResponse> snapshots,
			List<PostCommentResponse> recentComments) {
	}

	/** Snapshot(6.25) — 하루치 누적 지표. FEED는 views·shares·reposts를 어셈블러가 null로 강제한다. */
	public record SnapshotResponse(
			String date,
			Long views,
			Long likes,
			Long comments,
			Long saves,
			Long shares,
			Long reposts) {
	}

	/** PostComment(6.25) — author는 마스킹된 값만(AuthorMask), text·likes는 null 불가. */
	public record PostCommentResponse(
			String id,
			String author,
			String text,
			long likes,
			String createdAt,
			Reply reply) {

		/** 게시물 작성자(인플루언서) 본인 답글만. */
		public record Reply(String text) {
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
	 * 완전 조립(6.26 목록 조회·6.29 PATCH·6.30 cancel 공용) — {@code TrackingItemAssembler}가
	 * 상태 유도(ItemStatus)·post·recentComments·profileImageUrl·followers·lastUploadedAt까지
	 * 전부 채운 값을 넘긴다. nullable 필드는 계약 무결성 규칙 #1대로 명시적 null 그대로 통과시킨다.
	 */
	public static TrackingItemResponse full(long itemId, String mode, String status, String handle,
			String displayName, String profileImageUrl, Long followers, String lastUploadedAt, Long campaignId,
			String campaignName, String sourceUrl, LocalDate registeredOn, int trackingDays, Keywords keywords,
			TrackedPostResponse post, OffsetDateTime nextCheckAt) {
		return new TrackingItemResponse(
				String.valueOf(itemId), mode, status, handle, displayName, profileImageUrl, followers,
				lastUploadedAt, campaignId == null ? null : String.valueOf(campaignId), campaignName,
				sourceUrl, registeredOn.toString(), trackingDays, keywords, post,
				KstTimestamps.toKstIso(nextCheckAt));
	}
}
