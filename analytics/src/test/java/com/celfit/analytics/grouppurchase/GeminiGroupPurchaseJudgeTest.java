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
}
