package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 툴 선언 6종의 형태 검증(설계 §4) - 이름 오타·깨진 스키마는 런타임까지 안 가고 여기서 잡는다. */
class BrandAiToolSpecsTest {

	private final ObjectMapper om = new ObjectMapper();

	@Test
	void 설계가_정한_툴_8종이_그대로_선언된다() {
		assertThat(BrandAiToolSpecs.ALL).extracting(AiToolSpec::name)
				.containsExactly("list_brands", "list_posts", "search_posts", "aggregate_posts", "get_post",
						"get_comments", "list_hashtag_posts", "get_author");
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

	/**
	 * 강제 답변 문구 원인 분기(N3, 2026-08-28 재리뷰) - {@link BrandAiAgent}가 툴 상한 도달이면
	 * TOOL_CAP_NOTE를, 벽시계·토큰 예산 소진이면 TIME_BUDGET_NOTE를 골라 붙인다(실사용 확인은
	 * BrandAiAgentTest의 원인별 시나리오). 두 문구는 원인이 다르다는 사실 자체가 전제라 서로 달라야
	 * 하고, 각자 "추가 조회 없이" 지시를 담아야 한다.
	 */
	@Test
	void 강제_답변_문구_두_종류는_원인별로_다르고_각자_추가_조회_금지를_지시한다() {
		assertThat(BrandAiPrompt.TOOL_CAP_NOTE).contains("조회 가능 횟수").contains("추가 조회 없이");
		assertThat(BrandAiPrompt.TIME_BUDGET_NOTE).contains("답변 시간").contains("추가 조회 없이");
		assertThat(BrandAiPrompt.TOOL_CAP_NOTE).isNotEqualTo(BrandAiPrompt.TIME_BUDGET_NOTE);
	}
}
