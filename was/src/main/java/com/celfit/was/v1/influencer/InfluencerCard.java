package com.celfit.was.v1.influencer;

import java.math.BigDecimal;
import java.util.List;

/**
 * 스펙 6.21 발굴 목록 카드. 모든 파생 지표의 근거는 최근 12개 게시물 창(account_content_series).
 * id는 handle 그대로(6.4 확정 준용). email은 biography 정규식 파싱(V46, 스펙
 * 2026-07-30-influencer-email-from-bio) — 매치 없으면 null.
 * effectiveFollowers·avgViews·er는 산출 불가(ER·릴스 없음)면 null, bio·tagline 부재는 빈 문자열.
 * hypeScore: 계정 하입 스코어(최근창 콘텐츠 출력 매핑 점수 합 / 최근창 크기 고정 분모, 0~100) —
 * 점수 가능 콘텐츠 없으면 null(스펙 2026-07-29-influencer-avg-hype-score). 2026-07-30부터
 * 소수(BigDecimal) — account_summaries.avg_hype_score_precise를 그대로 싣는다(스펙
 * 2026-07-30-hype-score-v3-decay-after-mapping-design.md §10). 2026-07-31부터 분모가 창에 실제로
 * 든 콘텐츠 수가 아니라 창 크기로 고정돼(스펙 2026-07-31-account-score-fixed-denominator-design.md)
 * 단순 평균이 아니다 — 수집 누락으로 창을 못 채운 계정은 자연히 감점된다. 자리수 조정은 프론트 몫.
 * minComments·maxComments(2026-09-03 발굴 카드 확장): avgComments와 동일 창(account_content_series,
 * comments_count NULL 제외)의 최소·최대 — 유효 표본이 0이면(창 전체 미수집) null.
 * groupPurchaseCount·hasGroupPurchase(2026-09-03): 상세(6.4) recentContents와 같은 모수(최근 12개,
 * contents 테이블 posted_at 내림차순)에서 서버 판정 테이블 group_purchase_judgments.verdict=true인
 * 게시물 수와 그 존재 여부(스펙 2026-09-03-group-purchase-judgment-design.md §6). 판정은 analytics
 * GROUP_PURCHASE_JUDGE 잡(규칙 우선 + 애매분만 LLM, 30분 주기)이 채운다 — verdict가 NULL(미판정)이거나
 * 판정 행이 아예 없는 게시물은 세지 않는다(신뢰성 우선).
 */
public record InfluencerCard(String id, String handle, String displayName, String profileImageUrl,
		Long followers, Long effectiveFollowers, Long postsCount, Long followingCount, String bio,
		String email, String tagline, BigDecimal reachMultiplier, BigDecimal er, Long avgViews,
		Long avgLikes, Long avgComments, Integer minComments, Integer maxComments,
		BigDecimal hypeScore, Long sponsoredCount, int groupPurchaseCount, boolean hasGroupPurchase,
		List<String> collaboratedBrands, List<CategoryShare> categoryShares,
		List<RecentThumb> recentThumbs) {

	/** 대분류 비중 상위 최대 3개 — category는 5.5 슬러그, pct는 0~100 정수(분모 = 창 내 분류 게시물). */
	public record CategoryShare(String category, Integer pct) {
	}

	/** 카드 하단 썸네일 + hover 성과 툴팁 소스, postedAt 내림차순 최대 4개. 피드 views는 null(3.6). */
	public record RecentThumb(String contentId, String thumbnailUrl, String contentType,
			String mainCategory, String adType, String postedAt, Long views, Long likes,
			Long comments) {
	}
}
