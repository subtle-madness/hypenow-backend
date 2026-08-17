package com.celfit.monitoring.ad;

import java.util.List;

/** Tier0~3 최종 판정 — verdict 4종(DISCLOSED/NOT_DISCLOSED/INSUFFICIENT/UNCERTAIN) ·
 * source(RULE/LLM) · violations 코드 배열 · evidence 근거 문구(스펙 §4 컬럼과 1:1). */
public record AdVerdictResult(String verdict, String source, List<String> violations, List<Evidence> evidence) {

	public record Evidence(String phrase, String category, int offset) {
	}
}
