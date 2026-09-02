package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 프리셋 지시문·verified 플랜 매핑 검증(09-02 마케터 결정 중심 6종 개편). 구 계약 5종(FE 전환 전까지
 * 호환 유지)과 신 계약 6종이 각각 기대한 지시문·플랜을 돌려주는지, 미등록 presetId는 빈 값으로
 * 폴백하는지를 고정한다.
 */
class BrandAiPresetsTest {

	// ---------- 구 계약(08-28 FE 요청서), FE 전환 전까지 호환 유지 ----------

	@Test
	void efficient_influencers_지시문과_플랜() {
		assertThat(BrandAiPresets.instructionFor("efficient_influencers")).contains("효율 좋은 인플루언서");
		assertThat(BrandAiPresets.planFor("efficient_influencers")).containsExactly(new BrandAiPresets.PlannedCall(
				"aggregate_posts", "{\"groupBy\":\"author\",\"orderBy\":\"reachMultiple\",\"limit\":10,\"minSample\":2}"));
	}

	@Test
	void top_posts_지시문과_플랜() {
		assertThat(BrandAiPresets.instructionFor("top_posts")).contains("인기 게시물");
		assertThat(BrandAiPresets.planFor("top_posts"))
				.containsExactly(new BrandAiPresets.PlannedCall("list_posts", "{\"sort\":\"performance_desc\"}"));
	}

	@Test
	void sponsored_vs_organic_지시문과_플랜() {
		assertThat(BrandAiPresets.instructionFor("sponsored_vs_organic")).contains("협찬 vs 오가닉 비교");
		assertThat(BrandAiPresets.planFor("sponsored_vs_organic")).containsExactly(
				new BrandAiPresets.PlannedCall("aggregate_posts", "{\"groupBy\":\"sponsorship\"}"));
	}

	@Test
	void tagged_posts_analysis_지시문만_있고_플랜은_없다() {
		assertThat(BrandAiPresets.instructionFor("tagged_posts_analysis")).contains("태그된 게시물 분석");
		assertThat(BrandAiPresets.planFor("tagged_posts_analysis")).isEmpty();
	}

	@Test
	void paid_amplify_지시문만_있고_플랜은_없다() {
		assertThat(BrandAiPresets.instructionFor("paid_amplify")).contains("유료 증폭 후보");
		assertThat(BrandAiPresets.planFor("paid_amplify")).isEmpty();
	}

	// ---------- 신 계약(09-02 마케터 결정 중심 6종) ----------

	@Test
	void weekly_briefing_지시문과_플랜() {
		assertThat(BrandAiPresets.instructionFor("weekly_briefing")).contains("주간 브리핑");
		assertThat(BrandAiPresets.planFor("weekly_briefing")).containsExactly(
				new BrandAiPresets.PlannedCall("list_posts", "{\"days\":7,\"sort\":\"performance_desc\",\"limit\":10}"),
				new BrandAiPresets.PlannedCall("aggregate_posts", "{\"groupBy\":\"week\",\"days\":14}"));
	}

	@Test
	void organic_fans_지시문과_플랜() {
		assertThat(BrandAiPresets.instructionFor("organic_fans")).contains("오가닉 팬 찾기");
		assertThat(BrandAiPresets.planFor("organic_fans")).containsExactly(new BrandAiPresets.PlannedCall(
				"aggregate_posts", "{\"groupBy\":\"author\",\"sponsorship\":\"organic\",\"orderBy\":\"avgViews\",\"limit\":10}"));
	}

	@Test
	void sponsored_scorecard_지시문과_플랜() {
		assertThat(BrandAiPresets.instructionFor("sponsored_scorecard")).contains("협찬 성적표");
		assertThat(BrandAiPresets.planFor("sponsored_scorecard")).containsExactly(
				new BrandAiPresets.PlannedCall("aggregate_posts",
						"{\"groupBy\":\"author\",\"sponsorship\":\"sponsored\",\"orderBy\":\"avgViews\",\"limit\":20}"),
				new BrandAiPresets.PlannedCall("aggregate_posts", "{\"groupBy\":\"sponsorship\"}"));
	}

	@Test
	void ad_candidates_지시문과_플랜() {
		assertThat(BrandAiPresets.instructionFor("ad_candidates")).contains("광고 후보 게시물");
		assertThat(BrandAiPresets.planFor("ad_candidates")).containsExactly(
				new BrandAiPresets.PlannedCall("list_posts", "{\"sort\":\"performance_desc\",\"limit\":20}"));
	}

	@Test
	void negative_comments_지시문과_플랜() {
		assertThat(BrandAiPresets.instructionFor("negative_comments")).contains("부정 댓글 점검");
		assertThat(BrandAiPresets.planFor("negative_comments")).containsExactly(new BrandAiPresets.PlannedCall(
				"list_posts", "{\"days\":30,\"sort\":\"performance_desc\",\"limit\":5}"));
	}

	@Test
	void micro_creators_지시문과_플랜() {
		assertThat(BrandAiPresets.instructionFor("micro_creators")).contains("마이크로 크리에이터 발굴");
		assertThat(BrandAiPresets.planFor("micro_creators")).containsExactly(new BrandAiPresets.PlannedCall(
				"aggregate_posts", "{\"groupBy\":\"author\",\"orderBy\":\"reachMultiple\",\"limit\":30,\"minSample\":2}"));
	}

	// ---------- 미등록 presetId 폴백 ----------

	@Test
	void 미등록_presetId는_빈_지시문과_빈_플랜으로_폴백한다() {
		assertThat(BrandAiPresets.instructionFor("no-such-preset")).isEmpty();
		assertThat(BrandAiPresets.planFor("no-such-preset")).isEmpty();
	}

	@Test
	void presetId가_null이면_빈_지시문과_빈_플랜으로_폴백한다() {
		assertThat(BrandAiPresets.instructionFor(null)).isEmpty();
		assertThat(BrandAiPresets.planFor(null)).isEmpty();
	}

	@Test
	void 등록된_presetId_11종_전부_planFor가_예외없이_동작한다() {
		List<String> ids = List.of("efficient_influencers", "top_posts", "sponsored_vs_organic",
				"tagged_posts_analysis", "paid_amplify", "weekly_briefing", "organic_fans", "sponsored_scorecard",
				"ad_candidates", "negative_comments", "micro_creators");
		for (String id : ids) {
			assertThat(BrandAiPresets.instructionFor(id)).isNotEmpty();
			// planFor는 예외 없이 리스트(빈 리스트 포함)를 돌려줘야 한다.
			assertThat(BrandAiPresets.planFor(id)).isNotNull();
		}
	}
}
