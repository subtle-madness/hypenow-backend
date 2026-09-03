package com.celfit.analytics.admin;

/** 어드민이 트리거하는 analytics 잡. 라벨은 UI 표기 원본. */
public enum JobName {
	MIRROR("미러 — 분석 뷰 → analysis DB"),
	CLASSIFY("댓글 분류 (LLM)"),
	ANALYZE("콘텐츠 분석 (LLM)"),
	FACT_ANALYZE("콘텐츠 사실 분석 (LLM) - 파트 A, 캡션 전용"),
	ACCOUNT_ANALYZE("계정 카피 (LLM)"),
	SYNTHESIS_REFRESH("해석 문구 갱신 (LLM) — 사실 보존"),
	ARCHIVE("이미지 아카이브 — CDN→오브젝트 스토리지"),
	LATE_BACKFILL_ANALYZE("늦크롤 백필 분석 (LLM)"),
	TRAIT_CANON_DRY("trait 어휘 매핑 dry-run (LLM) — canon_log 기록만"),
	TRAIT_CANON_APPLY("trait 어휘 매핑 실행 (LLM) — traits UPDATE"),
	BATCH_COLLECT("배치 수거 — 콘텐츠 분석 배치(Vertex) 결과 회수"),
	GROUP_PURCHASE_JUDGE("공동구매 판정 (규칙 우선·애매분만 LLM)");

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
