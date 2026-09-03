package com.celfit.analytics.analyze;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * content_analyses에 저장된 파트 A 산출물을 파트 B 프롬프트의 "확인된 사실"로 옮기는 단일 원천.
 * 재생성 잡({@link ContentSynthesisRefreshJob})과 파트 B 배치 제출({@link ContentAnalysisJob})이
 * 같은 키 집합을 써야 두 경로의 문구가 서로 다른 사실을 근거로 삼는 일이 없다.
 * jsonb 컬럼은 문자열 그대로 넘긴다(LLM은 다시 판정하지 않고 주어진 대로 받는다).
 */
final class StoredFacts {

	/** 프롬프트에 싣는 사실 9키 - 순서가 프롬프트 표기 순서다. */
	static final List<String> KEYS = List.of("main_category", "sub_categories", "ad_type",
			"ad_disclosure", "detected_brands", "detected_products", "detected_product_categories",
			"sponsored_signal_level", "is_beauty");

	private StoredFacts() {
	}

	/** 조회 행(위 9키를 컬럼으로 가진 맵) → 프롬프트 입력 맵. */
	static Map<String, Object> of(Map<String, Object> row) {
		Map<String, Object> facts = new LinkedHashMap<>();
		for (String k : KEYS) {
			Object v = row.get(k);
			facts.put(k, v == null ? null : v.toString());
		}
		return facts;
	}

	/**
	 * 파트 A만 채워진 행(metric_timeliness='pending') 전량의 사실을 1회 조회로 맵에 담는다.
	 * 파트 B 배치가 콘텐츠마다 조회하면 제출 자체가 DB 왕복에 잠긴다(기준선 로딩과 같은 이유).
	 * 대상은 부분 인덱스 idx_content_analyses_timeliness_pending로 좁혀진다.
	 */
	static Map<String, Map<String, Object>> loadPending(JdbcTemplate analysis) {
		Map<String, Map<String, Object>> out = new LinkedHashMap<>();
		analysis.query("""
				SELECT short_code, main_category, sub_categories, ad_type, ad_disclosure,
				       detected_brands, detected_products, detected_product_categories,
				       sponsored_signal_level, is_beauty
				FROM content_analyses
				WHERE metric_timeliness = 'pending'""",
				rs -> {
					Map<String, Object> row = new LinkedHashMap<>();
					for (String k : KEYS) {
						row.put(k, rs.getObject(k));
					}
					out.put(rs.getString("short_code"), of(row));
				});
		return out;
	}
}
