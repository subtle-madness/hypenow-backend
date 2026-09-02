package com.celfit.analytics.llm;

import java.util.List;

/**
 * 콘텐츠 속성 분석 산출물 — 캡션 5종(광고 구분·카테고리·브랜드·제품·유통사) + 속성.
 * content_analyses의 NULL 허용 컬럼에 대응 (vlm_attributes 등 컬럼명은 서빙 계약이라 유지).
 * 분류 어휘는 {@link BeautyTaxonomy}(analysis DB 시드) — 어댑터 sanitize가 어휘 밖 값을 걸러낸다.
 * detectedProducts는 자유 텍스트(어휘 없음) — sanitize 대상 아님.
 *
 * <p><b>축 두 개 (2026-08-31)</b>: {@code isRelevant}는 LLM 응답 — 분류표의 어느 대분류에든
 * 해당하는 콘텐츠인가. {@code isBeauty}는 sanitize가 채우는 <b>파생값</b>으로, 확정된 대분류의
 * 축이 {@code beauty}일 때만 true다. 파생으로 둔 이유: 카테고리가 늘어도(F&amp;B·홈리빙)
 * content_analyses에 컬럼을 더하지 않고, was 소비처가 의존하는 is_beauty의 의미를 그대로
 * 보존하기 위해서다(설계 2026-08-31 §2). <b>sanitize 이전 인스턴스의 isBeauty는 항상 null이다.</b>
 */
public record ContentAttributes(List<Brand> detectedBrands, String sponsoredSignalLevel,
		List<String> sponsoredSignalReasons, String adDisclosure,
		List<String> detectedProductCategories, List<Product> detectedProducts,
		List<Attribute> vlmAttributes, String mainCategory, List<String> subCategories,
		List<String> detectedDistributors, String adType, Boolean isRelevant, Boolean isBeauty) {

	/**
	 * 미분류 종결 — 분류표 어느 대분류에도 확정되지 않은 사본. 분류 대상으로 판정됐으나 복구
	 * 후에도 대분류를 못 얻은 콘텐츠를 종결 저장할 때 쓴다(잡의 무한 재대상 루프 방지 — 분석이
	 * temperature 0 결정론이라 재실행해도 같은 결과다). 나머지 속성은 보존하되 서빙은
	 * is_beauty=false로 이미 제외하므로 노출되지 않는다.
	 */
	public ContentAttributes asUnclassified() {
		return new ContentAttributes(detectedBrands, sponsoredSignalLevel, sponsoredSignalReasons,
				adDisclosure, detectedProductCategories, detectedProducts, vlmAttributes,
				mainCategory, subCategories, detectedDistributors, adType, false, false);
	}

	public record Brand(String name, String evidence) {
	}

	public record Product(String name, String brand) {
	}

	public record Attribute(String label, String value) {
	}
}
