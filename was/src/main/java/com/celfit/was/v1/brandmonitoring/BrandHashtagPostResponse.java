package com.celfit.was.v1.brandmonitoring;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 해시태그 발견 게시물 응답(스펙 §8) — {@code GET
 * /v1/brand-monitoring/accounts/{accountId}/hashtag-posts} 전용 슬림 셰이프.
 *
 * <p><b>2026-08-27 직접 수집 전환 이후 리라우팅 전용 셰이프다</b>({@link BrandHashtagPostAssembler}
 * 참조) — 데이터 산지가 구 감지 테이블에서 {@link BrandPostResponse}(tagged·direct·hashtag 통합 풀)의
 * {@code source=hashtag} 부분집합으로 옮겨졌다. 이 record 자체는 그대로 두되(FE가 새 통합 목록으로
 * 전환하기 전까지 화면이 낡지 않게 하는 전환기 장치), 실재하지 않는 필드(latestSnapshot·snapshots·
 * commentsCollectedCount·recentComments·campaignIds·trackingStatus 등)를 null/빈 값으로 채워 넣는
 * 대신 실제로 있는 필드만 내려준다. <b>다음 릴리스에 이 record와 엔드포인트를 함께 제거</b>한다.
 *
 * <p>{@code likes}·{@code comments}는 이제 통합 풀의 <b>최신 스냅샷 값</b>이다(구 "발견 시점
 * 관측값"보다 신선하다) — 스냅샷이 아직 없으면 둘 다 null. {@code matchedTag}는 이 게시물을 찾아낸
 * 해시태그 원문이라 FE가 "#태그로 발견" 배지를 그릴 수 있다(매칭 기록이 없으면 null). {@code postUrl}은
 * 콘텐츠 타입과 무관하게 항상 {@code /p/} 경로다 — Instagram이 reels도 {@code /p/}를 {@code /reel/}로
 * 리다이렉트하므로 조회수 열거 없이도 안전하다.
 *
 * <p>{@code brandPostId}는 <b>항상 shortcode</b>다(2026-08-27 리라우팅 이후 — 구 승격 상태 조건부
 * 필드에서 전환) — 이 목록에 실리는 행은 이미 {@code source=hashtag}로 성과 측정 풀에 편입된
 * 게시물뿐이라 별도 존재 판정이 필요 없다.
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
		String firstSeenAt,
		String brandPostId) {
}
