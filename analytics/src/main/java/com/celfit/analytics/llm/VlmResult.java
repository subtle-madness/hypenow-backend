package com.celfit.analytics.llm;

import java.util.List;

/** VLM 산출물 (스펙 §3 — 전부 NULL 허용 컬럼에 대응). */
public record VlmResult(List<Brand> detectedBrands, String sponsoredSignalLevel,
		List<String> sponsoredSignalReasons, String adDisclosure,
		List<String> detectedProductCategories, List<Attribute> vlmAttributes,
		String mainCategory, List<String> subCategories, String adType) {

	public record Brand(String name, String evidence) {
	}

	public record Attribute(String label, String value) {
	}
}
