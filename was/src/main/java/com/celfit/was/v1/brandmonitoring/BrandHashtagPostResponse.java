package com.celfit.was.v1.brandmonitoring;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 해시태그 발견 게시물 응답(스펙 §8, 별도 탭 결정 2026-08-12) — {@code GET
 * /v1/brand-monitoring/accounts/{accountId}/hashtag-posts} 전용 슬림 셰이프.
 *
 * <p>{@link BrandPostResponse}(tagged·direct)와 의도적으로 <b>공유하지 않는다</b> — 이 산지는
 * 보강·스냅샷 파이프라인이 없어(브랜드 태그 모니터링과 별개 신규 저장소, 스펙 §5 보류) 실재하지
 * 않는 필드(latestSnapshot·snapshots·commentsCollectedCount·recentComments·campaignIds·
 * trackingStatus 등)를 null/빈 값으로 채워 넣는 대신, 실제로 있는 필드만 내려준다.
 *
 * <p>{@code likes}·{@code comments}는 <b>발견 시점 관측값</b>이다(재수집 없음 — 스냅샷처럼 갱신되지
 * 않는다). {@code matchedTag}는 이 게시물을 찾아낸 해시태그 원문이라 FE가 "#태그로 발견" 배지를
 * 그릴 수 있다. {@code postUrl}은 콘텐츠 타입과 무관하게 항상 {@code /p/} 경로다 — Instagram이
 * reels도 {@code /p/}를 {@code /reel/}로 리다이렉트하므로 조회수 열거 없이도 안전하다.
 */
public record BrandHashtagPostResponse(
		String shortcode,
		String postUrl,
		String matchedTag,
		String takenAt,
		String caption,
		@Schema(allowableValues = {"reels", "feed"}) String contentType,
		String thumbnailUrl,
		String authorUsername,
		String authorFullName,
		String authorProfilePicUrl,
		String authorProfileUrl,
		Long likes,
		Long comments,
		@Schema(allowableValues = {"sponsored", "organic", "unknown"}) String sponsorship,
		String firstSeenAt) {
}
