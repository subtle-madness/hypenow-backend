package com.celfit.analytics.llm;

import java.util.List;

/**
 * VLM 산출물 (스펙 §3 — 전부 NULL 허용 컬럼에 대응).
 * 분류 어휘는 {@link BeautyTaxonomy}(프론트 배포본 계약) — 어댑터 sanitize가 어휘 밖 값을 걸러낸다.
 */
public record VlmResult(List<Brand> detectedBrands, String sponsoredSignalLevel,
		List<String> sponsoredSignalReasons, String adDisclosure,
		List<String> detectedProductCategories, List<Attribute> vlmAttributes,
		String mainCategory, List<String> subCategories,
		List<String> detectedDistributors, String adType) {

	public record Brand(String name, String evidence) {
	}

	public record Attribute(String label, String value) {
	}
}
