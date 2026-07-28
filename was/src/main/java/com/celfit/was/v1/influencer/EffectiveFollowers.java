package com.celfit.was.v1.influencer;

import java.util.List;

/**
 * 유효 팔로워 = 게시물당 평균 실반응 팔로워 수(07-28 확정 산식) — 6.21 발굴 카드와 6.22 리포트가
 * 공용하는 단일 원천(스펙 7절 17번: 동일 소스·동일 값). 피어와 무관한 절대 측정 —
 * "관측 불가한 값을 추정 확장하지 마라, 100%는 장치가 아니라 현실이 막아야 한다"(07-28 원칙).
 */
public final class EffectiveFollowers {

	/** 산식 입력 1게시물 — views는 피드면 null(3.6), likes -1은 비공개 센티널(0으로 클램프). */
	public record Post(Long views, Long likes, Long comments) {
	}

	/** 댓글 앵커 계수 — 모집단 38,474게시물의 좋아요:댓글 비율 상위 90% 경계(07-28 실측 39:1).
	 *  이 비율을 크게 벗어나는 좋아요는 팔로워 반응으로 보지 않는다(탐색탭 유입·구매성 좋아요 컷). */
	private static final double LIKES_PER_COMMENT_ANCHOR = 39.0;

	/** 중복 계수 — 게시물당 반응을 "윈도우 중 1회 이상 반응한 고유 팔로워"로 확장할 때의
	 *  실효 독립 기회 비율(지수 = 게시물 수 × 0.25, 12개면 3). 임의 설정(07-28 확정) —
	 *  댓글 작성자 수집이 재개되면 계정별 실측 중복률로 대체 예정. */
	private static final double DUPLICATION_FACTOR = 0.25;

	private EffectiveFollowers() {
	}

	/**
	 * 유효 팔로워 = 팔로워 × (1 − (1−r)^(n×중복계수)) — "최근 n개 중 1회 이상 반응한 고유 팔로워" 추정.
	 * 선형 ×n은 같은 팔로워를 거듭 세어 팔로워 초과 모순이 나므로, 이미 반응한 팔로워를 다시 세지
	 * 않는 포함-배제 형태를 쓴다(보통 계정에선 ×3과 사실상 동일, 반응률 높은 계정만 중복 차감).
	 * r = 게시물당 평균 인정 반응 ÷ 팔로워 —
	 *   인정 반응 = min( (좋아요+댓글) × min(1, 팔로워/조회수),   ← 릴스 바이럴 안분
	 *                    ANCHOR × (댓글+1) )                       ← 비정상 좋아요 컷
	 * 운영 실측(07-28, 6,321계정): 중앙값 3.4%·상위 1% 45.7%·최대 89.9% — 90% 이상 0계정,
	 * 100%는 셈법 자체로 불가(캡·클램프 없음). 근거 없으면 null.
	 */
	public static Long estimate(Long followers, List<Post> posts) {
		if (followers == null || followers <= 0 || posts.isEmpty()) {
			return null;
		}
		double sum = 0;
		for (Post p : posts) {
			long likes = p.likes() == null ? 0 : Math.max(p.likes(), 0);
			long comments = p.comments() == null ? 0 : Math.max(p.comments(), 0);
			double engaged = likes + comments;
			if (p.views() != null && p.views() > followers) {
				engaged = engaged * followers / (double) p.views(); // 도달 중 팔로워 비중으로 안분
			}
			sum += Math.min(engaged, LIKES_PER_COMMENT_ANCHOR * (comments + 1));
		}
		double r = Math.min(sum / posts.size() / followers, 1.0);
		// 게시물이 적으면(지수 < 1) 확장이 축소로 뒤집히므로 하한 1 — 최소한 게시물당 측정값은 보장
		double exponent = Math.max(1.0, posts.size() * DUPLICATION_FACTOR);
		return Math.round(followers * (1 - Math.pow(1 - r, exponent)));
	}
}
