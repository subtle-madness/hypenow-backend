package com.celfit.analytics.llm;

/**
 * LLM 종합 산출 — 텍스트 3종 + 댓글 진정성 판정 (스펙 §3 content_analyses).
 *
 * <p>사실 추출(ContentAttributes)과 달리 <b>기준선 수치를 인용</b>하므로, 기준선 정의나 문구 의미가
 * 바뀌면 낡는다. {@link #VERSION}으로 어느 계약으로 생성됐는지를 행에 남겨
 * (content_analyses.synthesis_version) 낡은 행만 골라 이 부분만 다시 만든다.
 */
public record Synthesis(String aiContentSummary, String contentsPattern, String aiCommentInsight,
		String commentAuthenticityGrade, String commentAuthenticityNote) {

	/**
	 * 해석 문구 계약 버전 — <b>다음이 바뀌면 올린다</b>: 프롬프트의 문구 정의·의미,
	 * 기준선 산식(예: 07-21 참여율 분모 조회수→팔로워), 인용하는 지표의 종류.
	 * 사실 추출(브랜드·카테고리·ad_type)만 바뀐 경우는 해당 없다 — 그건 전체 재분석 사안이다.
	 *
	 * <p>1 = 07-21 기준(팔로워 분모 ER + contentsPattern 비교 해설 재정의 반영).
	 * 그 이전 행은 컬럼 자체가 없어 NULL로 남고, 갱신 잡이 NULL을 낡음으로 본다.
	 */
	public static final int VERSION = 1;
}
