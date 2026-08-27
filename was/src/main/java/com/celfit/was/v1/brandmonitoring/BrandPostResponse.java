package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.v1.monitoring.TrackingItemResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * BrandPost 응답(스펙 §6-1) — 브랜드 화면의 게시물 1건. tagged(브랜드 태그 테이블)와
 * direct(레거시 추적 아이템) 두 산지가 같은 셰이프로 내려간다({@link BrandPostAssembler}).
 *
 * <p>{@code id}는 shortcode다(스펙 §6-2 — {@code canonicalPostId}와 같은 값). 숫자 id가 아닌 이유는
 * tagged 게시물엔 was가 발급한 식별자가 없기 때문이고, 그래서 상세 조회 경로도 shortcode를 받는다.
 *
 * <p>스냅샷·댓글 셰이프는 레거시 {@link TrackingItemResponse}의 중첩 record를 그대로 재사용한다 —
 * FE가 이미 소비 중인 필드셋이라 브랜드 화면만 다른 모양을 갖게 하지 않는다(스펙 §6-1).
 * nullable 필드는 계약 무결성 규칙 #1대로 키를 생략하지 않고 명시적 null로 직렬화한다.
 *
 * <p>주의: {@code videoUrl}이 null이라고 "영상이 아님"으로 해석하면 안 된다 — 캐러셀 내부 영상은
 * 최상위 노드만 저장하는 설계라 null이 의도된 값이다(Task 1 이월). 매체 판별은 {@code contentType}이 정본.
 */
public record BrandPostResponse(
		String id,
		String brandAccountId,
		@Schema(allowableValues = {"tagged", "direct"}) String source,
		String postUrl,
		String shortcode,
		// 값은 "reels"/"feed" — 레거시 TrackedPost.contentType과 같은 어휘로 통일한다(direct 산지가
		// 레거시 값을 그대로 싣기 때문에, tagged만 "REELS"/"FEED"로 내려가면 한 필드에 두 어휘가 섞인다).
		@Schema(allowableValues = {"reels", "feed"}) String contentType,
		String takenAt,
		String caption,
		String thumbnailUrl,
		String videoUrl,
		Double videoDuration,
		String authorProfileUrl,
		String authorUsername,
		String authorFullName,
		String authorProfilePicUrl,
		boolean authorIsVerified,
		Long authorFollowers,
		@Schema(allowableValues = {"sponsored", "organic", "unknown"}) String sponsorship,
		Boolean isPaidPartnership,
		String trackingStatus,
		String trackingStartedAt,
		String trackingEndedAt,
		TrackingItemResponse.SnapshotResponse latestSnapshot,
		List<TrackingItemResponse.SnapshotResponse> snapshots,
		Long commentsTotal,
		boolean commentsHidden,
		long commentsCollectedCount,
		List<TrackingItemResponse.PostCommentResponse> recentComments,
		List<String> campaignIds,
		String createdAt,
		String updatedAt,
		// ---- 광고 표기 판정(2026-08-17 스펙 §9) ----
		@Schema(allowableValues = {"DISCLOSED", "NOT_DISCLOSED", "INSUFFICIENT", "UNCERTAIN"}) String adDisclosure,
		List<String> adViolations,
		List<AdEvidence> adEvidence,
		boolean seededAuthor) {

	/** 판정 근거 문구 1건 — monitoring ad_evidence jsonb 원소와 1:1(스펙 §4). */
	public record AdEvidence(String phrase, String category, int offset) {}

	/**
	 * 협찬 판정만 교체한 사본 — shortcode 병합에서 direct 본체를 유지한 채 tagged의
	 * {@code is_paid_partnership} 관측만 승격시키는 데 쓴다(정보 손실 방지, 스펙 §6-1).
	 * 광고 표기 필드는 이 메서드로는 건드리지 않는다(승격은 {@link #withAdFields}가 별도로 맡는다) —
	 * direct에는 이 필드들의 산지가 애초에 없으므로 겹치는 tagged가 있으면 반드시 승격해야 한다.
	 */
	public BrandPostResponse withSponsorship(String sponsorship, Boolean isPaidPartnership) {
		return new BrandPostResponse(id, brandAccountId, source, postUrl, shortcode, contentType, takenAt,
				caption, thumbnailUrl, videoUrl, videoDuration, authorProfileUrl, authorUsername, authorFullName,
				authorProfilePicUrl, authorIsVerified, authorFollowers, sponsorship, isPaidPartnership,
				trackingStatus, trackingStartedAt, trackingEndedAt, latestSnapshot, snapshots, commentsTotal,
				commentsHidden, commentsCollectedCount, recentComments, campaignIds, createdAt, updatedAt,
				adDisclosure, adViolations, adEvidence, seededAuthor);
	}

	/**
	 * 댓글만 비운 사본(2026-08-27 목록 타임아웃 해소 — FE 요청 1) — 목록 표면은 댓글을 렌더하지
	 * 않아 {@code recentComments}를 싣지 않는다(상세만 포함). 계약 무결성 규칙 #1(키 생략 금지)대로
	 * 키는 유지하고 빈 목록·0으로 내린다. 스냅샷 유래 지표({@code commentsTotal}·
	 * {@code commentsHidden})는 그대로다 — 댓글 수 표시는 그쪽이 정본이다.
	 */
	public BrandPostResponse withoutRecentComments() {
		return new BrandPostResponse(id, brandAccountId, source, postUrl, shortcode, contentType, takenAt,
				caption, thumbnailUrl, videoUrl, videoDuration, authorProfileUrl, authorUsername, authorFullName,
				authorProfilePicUrl, authorIsVerified, authorFollowers, sponsorship, isPaidPartnership,
				trackingStatus, trackingStartedAt, trackingEndedAt, latestSnapshot, snapshots, commentsTotal,
				commentsHidden, 0L, List.of(), campaignIds, createdAt, updatedAt,
				adDisclosure, adViolations, adEvidence, seededAuthor);
	}

	/**
	 * 광고 표기 판정 4필드만 교체한 사본 — shortcode 병합에서 direct 본체를 유지한 채 tagged의
	 * 판정값만 승격시키는 데 쓴다({@link #withSponsorship}과 같은 관용구, 정보 손실 방지 —
	 * 코디네이터 스펙 리뷰 반영). direct 산지엔 이 필드들의 원천(brand_post_meta)이 없으므로
	 * tagged 값이 항상 정본이다 — 토글 off로 tagged 쪽이 이미 중립값(null/빈 목록/false)이면
	 * 승격해도 무해하다(별도 분기 불필요).
	 */
	public BrandPostResponse withAdFields(String adDisclosure, List<String> adViolations,
			List<AdEvidence> adEvidence, boolean seededAuthor) {
		return new BrandPostResponse(id, brandAccountId, source, postUrl, shortcode, contentType, takenAt,
				caption, thumbnailUrl, videoUrl, videoDuration, authorProfileUrl, authorUsername, authorFullName,
				authorProfilePicUrl, authorIsVerified, authorFollowers, sponsorship, isPaidPartnership,
				trackingStatus, trackingStartedAt, trackingEndedAt, latestSnapshot, snapshots, commentsTotal,
				commentsHidden, commentsCollectedCount, recentComments, campaignIds, createdAt, updatedAt,
				adDisclosure, adViolations, adEvidence, seededAuthor);
	}
}
