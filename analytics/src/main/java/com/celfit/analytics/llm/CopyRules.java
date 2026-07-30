package com.celfit.analytics.llm;

/**
 * 계정 카피 재생성 버전 게이트 (설계 2026-07-30-perf-summary-statistical-guards §4).
 *
 * <p>{@code account_analyses.copy_version}에 이 값을 기록해두고, {@link
 * com.celfit.analytics.analyze.AccountAnalysisJob#ELIGIBLE_WHERE}가 이 값보다 낮은 최신 행을
 * 낡음으로 보고 자연 재대상 잡는다. 새 게시물을 올리지 않는 계정은 문구가 영구 고정되는
 * 문제(§4)를 푸는 장치 — {@link Synthesis#VERSION}이 이미 쓰는 관용구를 계정 카피에도 적용한 것.
 *
 * <p><b>다음이 바뀌면 올린다</b>: {@link PerfConfidence}의 등급 판정 임계값·문구, 프롬프트의
 * 신뢰도 지침 문구 자체. 올리면 기존 행은 낮은 버전으로 남아 전량 재생성 대상이 되고, 재생성될 때
 * 비로소 새 판정 기준으로 쓰인 문구가 실린다.
 */
public final class CopyRules {

	/** 1 = 07-30 성과 요약 통계 왜곡 가드 도입(PerfConfidence 판정 + median 근거 전환) 반영. */
	public static final int VERSION = 1;

	private CopyRules() {}
}
