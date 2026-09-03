package com.celfit.was.v1.brandmonitoring;

/**
 * 브랜드를 태그한 게시물을 작성자별로 접은 요약 1행 — celfit-front
 * {@code src/lib/monitoring/brand-influencers.ts}의 {@code BrandInfluencerSummary} 1:1 이식
 * (2026-08-27). 규칙 변경은 FE와 동시에.
 *
 * <p>파생 지표(평균 조회수·참여율)는 담지 않는다: FE가 같은 계산식을 이미 갖고 있고, 여기에
 * 반올림된 값을 실어 보내면 정렬(서버)과 표시(FE)가 서로 다른 수를 근거로 삼게 된다.
 *
 * @param username 계정명. 집계 키라 항상 비어 있지 않다.
 * @param fullName 닉네임. 서버는 미수집이면 null이다(FE는 항상 문자열 — 어댑터가 접는다).
 * @param profilePicUrl 프로필 이미지. 미수집이면 null.
 * @param profileUrl 인스타그램 프로필 링크(계정명에서 파생).
 * @param followers 팔로워 수. 프로필 미수집이면 null — 0으로 접지 않는다(참여율이 무한대가 된다).
 * @param postCount 이 브랜드를 태그한 게시물 수.
 * @param sponsoredCount 협찬으로 판별된 게시물 수. 미판별(unknown)은 세지 않는다.
 * @param views 조회수 합. 피드 게시물은 구조적으로 null이라 0을 더한다.
 * @param likes 좋아요 합 — <b>셀 수 있었던 게시물만</b> 더한 값이다.
 * @param comments 댓글 합.
 * @param likesKnownCount 좋아요를 실제로 센 게시물 수. 0이면 {@code likes}와 참여율은 의미가 없다.
 * @param latestPostAt 가장 최근 게시물의 KST 게시 시각(팔로워 값의 시점 근거).
 * @param influencerId 2026-09-03, 브랜드 모니터링 저장 연동 — {@code username}이 발굴 상세 조회
 *                     (GET /v1/influencers/{influencerId})가 성공하는 계정이면 그 handle, 아니면
 *                     null. POST /v1/saved-influencers 저장에 그대로 쓴다. username 단위로 결정되는
 *                     값이라 이 작성자의 게시물이 몇 건이든 항상 같다({@link BrandInfluencerAggregator}).
 */
public record BrandInfluencerResponse(String username, String fullName, String profilePicUrl,
		String profileUrl, Long followers, long postCount, long sponsoredCount, long views,
		long likes, long comments, long likesKnownCount, String latestPostAt, String influencerId) {
}
