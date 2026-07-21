package com.celfit.analytics.llm;

import java.util.List;

/**
 * 콘텐츠 속성 분석 산출물 — 캡션 5종(광고 구분·카테고리·브랜드·제품·유통사) + 속성.
 * content_analyses의 NULL 허용 컬럼에 대응 (vlm_attributes 등 컬럼명은 서빙 계약이라 유지).
 * 분류 어휘는 {@link BeautyTaxonomy}(analysis DB 시드) — 어댑터 sanitize가 어휘 밖 값을 걸러낸다.
 * detectedProducts는 자유 텍스트(어휘 없음) — sanitize 대상 아님.
 * isBeauty는 콘텐츠 단위 뷰티 여부(어휘 없음 — sanitize 통과, 잡이 실패 판정에 사용).
 */
public record ContentAttributes(List<Brand> detectedBrands, String sponsoredSignalLevel,
		List<String> sponsoredSignalReasons, String adDisclosure,
		List<String> detectedProductCategories, List<Product> detectedProducts,
		List<Attribute> vlmAttributes, String mainCategory, List<String> subCategories,
		List<String> detectedDistributors, String adType, Boolean isBeauty) {

	/**
	 * 비뷰티 다운그레이드 — isBeauty만 false로 바꾼 사본. 뷰티로 판정됐으나 복구 후에도 대분류를
	 * 못 얻은 콘텐츠를 종결 저장할 때 쓴다(잡의 무한 재대상 루프 방지). 나머지 속성은 보존하되
	 * 서빙은 is_beauty=false로 이미 제외하므로 노출되지 않는다.
	 */
	public ContentAttributes asNonBeauty() {
		return new ContentAttributes(detectedBrands, sponsoredSignalLevel, sponsoredSignalReasons,
				adDisclosure, detectedProductCategories, detectedProducts, vlmAttributes,
				mainCategory, subCategories, detectedDistributors, adType, false);
	}

	public record Brand(String name, String evidence) {
	}

	public record Product(String name, String brand) {
	}

	public record Attribute(String label, String value) {
	}
}
