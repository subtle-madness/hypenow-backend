package com.celfit.analytics.admin;

/** 어드민이 트리거하는 analytics 잡. 라벨은 UI 표기 원본. */
public enum JobName {
	MIRROR("미러 — 분석 뷰 → analysis DB"),
	CLASSIFY("댓글 분류 (LLM)"),
	ANALYZE("콘텐츠 분석 (LLM)"),
	ACCOUNT_ANALYZE("계정 카피 (LLM)"),
	ARCHIVE("이미지 아카이브 — CDN→오브젝트 스토리지");

	private final String label;

	JobName(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** URL 경로 조각 — account-analyze 식 소문자 하이픈. */
	public String slug() {
		return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
	}

	public static JobName fromSlug(String slug) {
		return valueOf(slug.toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
	}
}
