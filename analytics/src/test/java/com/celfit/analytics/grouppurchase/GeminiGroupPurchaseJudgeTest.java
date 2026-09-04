package com.celfit.analytics.grouppurchase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.analytics.llm.GeminiApi;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 공동구매 애매분 LLM 어댑터 계약: 요청 조립·응답 파싱·형식 오류 처리. */
class GeminiGroupPurchaseJudgeTest {

	record Call(String model, String system, String user, String schema) {}

	List<Call> calls = new ArrayList<>();

	GeminiApi fakeApi(String response) {
		return (model, system, user, image, schema, maxTokens) -> {
			calls.add(new Call(model, system, user, schema));
			return response;
		};
	}

	/** 호출 순서대로 다른 응답을 준다 — 모순 가드의 재질의 시나리오 검증용. */
	GeminiApi fakeApiSequence(String... responses) {
		List<String> queue = new ArrayList<>(List.of(responses));
		return (model, system, user, image, schema, maxTokens) -> {
			calls.add(new Call(model, system, user, schema));
			return queue.remove(0);
		};
	}

	@Test
	void 정상_응답을_Judgment로_파싱한다() {
		GeminiGroupPurchaseJudge judge = new GeminiGroupPurchaseJudge(
				fakeApi("""
						{"groupPurchase":true,"reason":"캡션에 공구 오픈·주문서 안내가 있어 판매 게시물로 판단"}"""),
				() -> "gemini-3.1-flash-lite");

		GroupPurchaseJudgePort.Judgment j = judge.judge("이번주 공구 오픈합니다");

		assertTrue(j.groupPurchase());
		assertEquals("캡션에 공구 오픈·주문서 안내가 있어 판매 게시물로 판단", j.reason());
		assertEquals("gemini-3.1-flash-lite", calls.get(0).model());
	}

	@Test
	void groupPurchase_false_응답도_그대로_전달된다() {
		GeminiGroupPurchaseJudge judge = new GeminiGroupPurchaseJudge(
				fakeApi("""
						{"groupPurchase":false,"reason":"공구가 연장 의미로만 쓰였다"}"""),
				() -> "gemini-3.1-flash-lite");

		GroupPurchaseJudgePort.Judgment j = judge.judge("공구 없이 조립했어요");

		assertFalse(j.groupPurchase());
		assertEquals("공구가 연장 의미로만 쓰였다", j.reason());
	}

	@Test
	void 캡션이_유저_입력에_실린다() {
		GeminiGroupPurchaseJudge judge = new GeminiGroupPurchaseJudge(
				fakeApi("""
						{"groupPurchase":true,"reason":"근거"}"""),
				() -> "m");

		judge.judge("이번주 공구 오픈합니다");

		assertTrue(calls.get(0).user().contains("이번주 공구 오픈합니다"));
		assertTrue(calls.get(0).schema().contains("groupPurchase"));
	}

	@Test
	void 잘못된_JSON은_예외를_던진다() {
		GeminiGroupPurchaseJudge judge = new GeminiGroupPurchaseJudge(
				fakeApi("이건 JSON이 아님"), () -> "m");

		assertThrows(RuntimeException.class, () -> judge.judge("공구 오픈"));
	}

	@Test
	void 필수_필드가_빠지면_예외를_던진다() {
		GeminiGroupPurchaseJudge judge = new GeminiGroupPurchaseJudge(
				fakeApi("""
						{"groupPurchase":true}"""), () -> "m");

		assertThrows(RuntimeException.class, () -> judge.judge("공구 오픈"));
	}

	@Test
	void 사유_모순_true_는_재질의하고_거짓_응답이면_거짓으로_확정된다() {
		GeminiGroupPurchaseJudge judge = new GeminiGroupPurchaseJudge(
				fakeApiSequence(
						"""
						{"groupPurchase":true,"reason":"판매 신호 없는 후기 글"}""",
						"""
						{"groupPurchase":false,"reason":"판매 신호가 없어 후기로 판단"}"""),
				() -> "m");

		GroupPurchaseJudgePort.Judgment j = judge.judge("트로마츠 1년 써보고 써본 후기");

		assertFalse(j.groupPurchase());
		assertEquals(2, calls.size());
		assertTrue(calls.get(1).user().contains("판매 신호 없는 후기 글"));
	}

	@Test
	void 사유_모순_true_는_재질의하고_정상_사유의_참이면_참으로_확정된다() {
		GeminiGroupPurchaseJudge judge = new GeminiGroupPurchaseJudge(
				fakeApiSequence(
						"""
						{"groupPurchase":true,"reason":"리뷰 형식이지만 공구 아님"}""",
						"""
						{"groupPurchase":true,"reason":"공구 오픈 9/10~9/12, 주문서 링크 안내"}"""),
				() -> "m");

		GroupPurchaseJudgePort.Judgment j = judge.judge("공구템 써보고 다시 공구 오픈합니다");

		assertTrue(j.groupPurchase());
		assertEquals("공구 오픈 9/10~9/12, 주문서 링크 안내", j.reason());
		assertEquals(2, calls.size());
	}

	@Test
	void 재질의도_모순이면_거짓으로_처리하고_모순_가드_문구를_남긴다() {
		GeminiGroupPurchaseJudge judge = new GeminiGroupPurchaseJudge(
				fakeApiSequence(
						"""
						{"groupPurchase":true,"reason":"인플루언서 공구템 실패한 것도 있고 하는 후기 글"}""",
						"""
						{"groupPurchase":true,"reason":"공구가 아니라 사용기에 가깝다"}"""),
				() -> "m");

		GroupPurchaseJudgePort.Judgment j = judge.judge("인플루언서 공구템 실패한것도 있고");

		assertFalse(j.groupPurchase());
		assertTrue(j.reason().contains("모순 가드"));
		assertEquals(2, calls.size());
	}

	@Test
	void 사유가_모순되지_않는_참은_재질의_없이_한_번만_호출한다() {
		GeminiGroupPurchaseJudge judge = new GeminiGroupPurchaseJudge(
				fakeApi("""
						{"groupPurchase":true,"reason":"공구 오픈 3일 한정, 링크 클릭"}"""),
				() -> "m");

		GroupPurchaseJudgePort.Judgment j = judge.judge("공구 오픈 3일 한정");

		assertTrue(j.groupPurchase());
		assertEquals(1, calls.size());
	}

	@Test
	void 거짓_응답은_사유에_후기가_있어도_가드_대상이_아니라_한_번만_호출한다() {
		GeminiGroupPurchaseJudge judge = new GeminiGroupPurchaseJudge(
				fakeApi("""
						{"groupPurchase":false,"reason":"판매 신호 없는 후기"}"""),
				() -> "m");

		GroupPurchaseJudgePort.Judgment j = judge.judge("알고리즘에 지배당해 과소비한 후기");

		assertFalse(j.groupPurchase());
		assertEquals(1, calls.size());
	}

	@Test
	void 시스템_지시에_후기와_판매_신호_문구가_포함된다() {
		assertTrue(GeminiGroupPurchaseJudge.INSTRUCTIONS.contains("후기"));
		assertTrue(GeminiGroupPurchaseJudge.INSTRUCTIONS.contains("판매 신호"));
	}
}
