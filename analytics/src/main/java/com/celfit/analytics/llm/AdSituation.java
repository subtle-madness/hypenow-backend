package com.celfit.analytics.llm;

/**
 * 계정의 광고 활동 상황 — adSummary(07-27 개편 전 이름: adHeadline)가 무엇을 진술할지 가르는 4분기.
 *
 * <p>원래는 "organic vs 협찬 비교가 되는가"(불리언) 하나로 갈라 비교가 안 되면 헤드라인을 NULL로
 * 버렸는데, 협찬이 아예 없는 것도·전량 협찬인 것도 마케터에겐 정보라 빈칸이 될 이유가 없다
 * (07-21 PO 결정 — 운영 실측 207계정/22%가 이 이유로 영구 NULL이었다).
 * 진짜로 할 말이 없는 건 측정 가능한 게시물 자체가 없는 {@link #INSUFFICIENT}뿐이다.
 *
 * <p>기준지표(views 또는 likes)가 0보다 큰 게시물만 "측정 가능"으로 센다 — 피드는 조회수가
 * 항상 NULL이라 metric=views인 계정에서 통째로 빠진다.
 */
public enum AdSituation {

	/** organic·협찬 둘 다 측정 가능 — 평균 비교를 수치로 진술한다. */
	COMPARABLE("비교 가능"),

	/** 협찬 표기 게시물이 하나도 없음 — 협찬 이력이 없다는 사실과 그 기간 지표를 진술한다. */
	NO_ADS("협찬 없음"),

	/** 측정 가능분이 전량 협찬 — 비교 대상 organic이 없어 협찬 비중·성과만 진술한다. */
	ALL_ADS("전량 협찬"),

	/** 측정 가능한 게시물이 없어 근거가 없음 — adSummary를 만들지 않는다(저장 시 NULL). */
	INSUFFICIENT("판단 불가");

	private final String label;

	AdSituation(String label) {
		this.label = label;
	}

	/** 프롬프트에 그대로 실리는 한글 라벨 — 지시문의 케이스 이름과 1:1로 맞춘다. */
	public String label() {
		return label;
	}

	/** adSummary 생성·저장 대상인가 (근거 없는 INSUFFICIENT만 제외). 메서드명은 구 headline 시절 그대로 유지. */
	public boolean writesHeadline() {
		return this != INSUFFICIENT;
	}
}
