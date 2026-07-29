package com.celfit.analytics.llm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

/**
 * trait 배치 매핑 Gemini 어댑터 (2026-07-29 어휘 통제 스펙 §3-3). 원샷 이행 전용이라
 * Gemini/Vertex만 지원한다(ContentSynthesisPort 선례 — 프로바이더 폴백까지 둘 필요 없음).
 */
public final class GeminiTraitMapper implements TraitMappingPort {

	static final int MAX_OUTPUT_TOKENS = 8192;

	static final String RESPONSE_SCHEMA = """
			{"type":"object","properties":{
			  "mappings":{"type":"array","items":{"type":"object","properties":{
			    "raw":{"type":"string"},"canon":{"type":"array","items":{"type":"string"}}},
			    "required":["raw","canon"]}}},
			 "required":["mappings"]}""";

	static final String INSTRUCTIONS_TEMPLATE = """
			당신은 인스타그램 뷰티 인플루언서 분석 데이터의 태그 정규화 담당자다. 입력의 자유 서술
			성향 태그 각각을 아래 고정 어휘의 태그로 매핑하라.

			- 각 raw 태그당 canon 최대 2개 — 의미가 같거나 가장 가까운 어휘 태그. 복합 표현은 원자
			  태그 2개로 분해한다(예: "감성 브이로그" → ["브이로그", "감성 무드"]).
			- 어휘 표기를 그대로 쓴다(변형·조어 금지). 일본어 등 외국어 태그도 의미로 매핑한다.
			- 어휘에 대응하는 의미가 없으면 canon을 빈 배열로 둔다. 억지 매핑 금지.
			- 입력의 모든 raw를 빠짐없이 한 번씩 출력한다.

			어휘:
			%s""";

	record Mapping(String raw, List<String> canon) {}

	record Mappings(List<Mapping> mappings) {}

	private final GeminiApi api;
	private final Supplier<String> model;
	private final Supplier<TraitTaxonomy> vocab;
	private final ObjectMapper om = new ObjectMapper();

	public GeminiTraitMapper(GeminiApi api, Supplier<String> model, Supplier<TraitTaxonomy> vocab) {
		this.api = api;
		this.model = model;
		this.vocab = vocab;
	}

	@Override
	public Map<String, List<String>> map(List<String> rawValues) {
		String out = api.generateJson(model.get(),
				INSTRUCTIONS_TEMPLATE.formatted(vocab.get().promptBlock().indent(2).stripTrailing()),
				String.join("\n", rawValues), null, RESPONSE_SCHEMA, MAX_OUTPUT_TOKENS);
		Mappings parsed = om.readValue(out, Mappings.class);
		Map<String, List<String>> result = new HashMap<>();
		for (Mapping m : parsed.mappings()) {
			if (m.raw() != null && m.canon() != null) {
				result.put(m.raw(), m.canon());
			}
		}
		return result;
	}
}
