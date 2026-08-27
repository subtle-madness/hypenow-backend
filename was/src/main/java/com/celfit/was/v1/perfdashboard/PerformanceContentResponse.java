package com.celfit.was.v1.perfdashboard;

import com.celfit.was.v1.monitoring.TrackingItemResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 성과 대시보드 콘텐츠 1건(스펙 §7-1) — 레거시 추적 아이템(individual·direct)과 브랜드 태그
 * 관측(tagged)을 shortcode로 합친 결과다.
 *
 * <p>{@code item}은 레거시 TrackingItem과 같은 셰이프를 유지한다(FE가 이미 소비 중인 필드셋).
 * 다른 점은 {@code post}가 브랜드 화면과 같은 댓글 집계 3필드(commentsTotal·commentsHidden·
 * commentsCollectedCount)와 shortcode를 추가로 싣는다는 것뿐이라, 레거시 응답 DTO를 건드리지 않고
 * 대시보드 전용 record로 분리했다(레거시 동결 제약).
 *
 * <p>{@code source}는 대표 산지 1개다 — 우선순위 individual &gt; direct &gt; tagged(설계 결정 7).
 * 같은 게시물이 여러 산지에서 관측되면 대표가 아닌 쪽은 {@code additionalSources}로 남는다.
 * {@code canonicalPostId}는 shortcode(순수 값)이고, 게시물이 아직 없는 아이템(collecting·detecting·
 * not_uploaded)은 null이다 — 그 상태의 콘텐츠는 {@code item.id}로만 식별된다.
 *
 * <p>nullable 필드는 계약 무결성 규칙 #1대로 키를 생략하지 않고 명시적 null로 직렬화한다.
 */
public record PerformanceContentResponse(
		PerformanceItemResponse item,
		@Schema(allowableValues = {"individual", "direct", "tagged"}) String source,
		@Schema(allowableValues = {"sponsored", "organic", "unknown"}) String sponsorship,
		String canonicalPostId,
		List<String> additionalSources,
		String brandAccountId) {

	/**
	 * 대시보드 아이템 — 레거시 {@link TrackingItemResponse}와 같은 필드 순서·의미를 유지한다.
	 * tagged-only 콘텐츠는 레거시 행이 없어 여기 값을 합성한다(id는 {@code "bt_"+shortcode} —
	 * 레거시 숫자 id와 충돌하지 않게 접두를 붙인다, 설계 결정 7).
	 */
	public record PerformanceItemResponse(
			String id,
			@Schema(allowableValues = {"url", "account"}) String mode,
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
			TrackingItemResponse.Keywords keywords,
			PerformancePostResponse post,
			String nextCheckAt) {
	}

	/**
	 * 대시보드 게시물 — 레거시 TrackedPost + shortcode + 댓글 집계 3필드.
	 *
	 * <p>{@code uploadedAt}은 산지에 따라 날짜(레거시·direct)와 타임스탬프(tagged)가 섞인다 —
	 * 레거시 {@code uploaded_at}이 date 컬럼이라 정밀도를 만들어낼 수 없고, tagged의 시각을 버리는
	 * 것도 정보 손실이라 그대로 싣는다. 비교·필터는 항상 앞 10자 날짜 기준이다
	 * ({@link PerformanceContentAssembler#uploadedOn}).
	 */
	public record PerformancePostResponse(
			String url,
			String shortcode,
			@Schema(allowableValues = {"reels", "feed"}) String contentType,
			String uploadedAt,
			String caption,
			List<String> matchedKeywords,
			String thumbnailUrl,
			String hiddenAt,
			List<TrackingItemResponse.SnapshotResponse> snapshots,
			PreviousDayValues previousDayValues,
			Long commentsTotal,
			boolean commentsHidden,
			long commentsCollectedCount,
			List<TrackingItemResponse.PostCommentResponse> recentComments) {
	}

	/** 직전 스냅샷의 지표 3종(FE "▲오늘" 증가분 재료, 2026-08-27) — 직전 스냅샷이 없으면 객체 자체가 null. */
	public record PreviousDayValues(Long views, Long likes, Long comments) {
	}

	/**
	 * snapshotMode=latest(2026-08-27) — 스냅샷을 최신 1개로 줄인 사본. previousDayValues는 전체
	 * 시계열에서 이미 계산돼 있어 그대로 보존된다(잘라낸 뒤 계산하면 항상 null이 되므로 순서 불변).
	 */
	public PerformanceContentResponse withLatestSnapshotOnly() {
		PerformancePostResponse post = item().post();
		if (post == null || post.snapshots().size() <= 1) {
			return this;
		}
		List<TrackingItemResponse.SnapshotResponse> latest = List.of(post.snapshots().get(post.snapshots().size() - 1));
		PerformancePostResponse trimmed = new PerformancePostResponse(post.url(), post.shortcode(),
				post.contentType(), post.uploadedAt(), post.caption(), post.matchedKeywords(),
				post.thumbnailUrl(), post.hiddenAt(), latest, post.previousDayValues(), post.commentsTotal(),
				post.commentsHidden(), post.commentsCollectedCount(), post.recentComments());
		return new PerformanceContentResponse(new PerformanceItemResponse(item().id(), item().mode(),
				item().status(), item().handle(), item().displayName(), item().profileImageUrl(),
				item().followers(), item().lastUploadedAt(), item().campaignId(), item().campaignName(),
				item().sourceUrl(), item().registeredAt(), item().trackingDays(), item().keywords(), trimmed,
				item().nextCheckAt()), source(), sponsorship(), canonicalPostId(), additionalSources(),
				brandAccountId());
	}
}
