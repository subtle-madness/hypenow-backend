package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 툴 선언 6종의 형태 검증(설계 §4) - 이름 오타·깨진 스키마는 런타임까지 안 가고 여기서 잡는다. */
class BrandAiToolSpecsTest {

	private final ObjectMapper om = new ObjectMapper();

	@Test
	void 설계가_정한_툴_6종이_그대로_선언된다() {
		assertThat(BrandAiToolSpecs.ALL).extracting(AiToolSpec::name)
				.containsExactly("list_brands", "list_posts", "get_post", "get_comments",
						"list_hashtag_posts", "get_author");
	}

	@Test
	void 모든_툴_스키마가_파싱_가능한_object_타입이다() {
		for (AiToolSpec spec : BrandAiToolSpecs.ALL) {
			assertThat(spec.description()).isNotBlank();
			if (spec.parametersJson() != null) {
				assertThat(om.readTree(spec.parametersJson()).path("type").asString()).isEqualTo("object");
			}
		}
	}

	@Test
	void 인자가_없는_list_brands만_스키마가_null이다() {
		List<String> withoutSchema = BrandAiToolSpecs.ALL.stream()
				.filter(spec -> spec.parametersJson() == null).map(AiToolSpec::name).toList();
		assertThat(withoutSchema).containsExactly("list_brands");
	}

	@Test
	void 시스템_프롬프트는_도메인_밖_질문_거절을_지시한다() {
		assertThat(BrandAiPrompt.SYSTEM).contains("브랜드 모니터링").contains("답할 수 없어요");
		assertThat(BrandAiPrompt.TOOL_CAP_NOTE).contains("지금까지");
	}
}
