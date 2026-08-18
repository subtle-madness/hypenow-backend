package com.celfit.monitoring.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BrandMentionJudgeTest {

	private static String geminiBody(String verdict) {
		return """
				{"candidates":[{"content":{"parts":[{"text":"{\\"verdict\\":\\"%s\\"}"}]}}]}"""
				.formatted(verdict);
	}

	@Test
	void 관련_판정을_파싱한다() {
		BrandMentionJudge judge = new BrandMentionJudge((path, body) -> geminiBody("RELEVANT"), true, "model-x");
		assertThat(judge.judge("cclime_official", null, List.of("끌리메", "cclime"), "poster1", "끌리메 후기"))
				.isEqualTo(BrandMentionJudge.Verdict.RELEVANT);
	}

	@Test
	void 무관과_불확실도_파싱한다() {
		assertThat(new BrandMentionJudge((p, b) -> geminiBody("IRRELEVANT"), true, "m")
				.judge("u", null, List.of("t"), "p", "c")).isEqualTo(BrandMentionJudge.Verdict.IRRELEVANT);
		assertThat(new BrandMentionJudge((p, b) -> geminiBody("UNCERTAIN"), true, "m")
				.judge("u", null, List.of("t"), "p", "c")).isEqualTo(BrandMentionJudge.Verdict.UNCERTAIN);
	}

	@Test
	void 요청_경로와_바디에_모델_브랜드_컨텍스트_캡션이_실린다() {
		AtomicReference<String> sent = new AtomicReference<>();
		BrandMentionJudge judge = new BrandMentionJudge((path, body) -> {
			sent.set(path + "\n" + body);
			return geminiBody("RELEVANT");
		}, true, "model-x");
		judge.judge("cclime_official", null, List.of("끌리메", "cclime"), "poster1", "끌리메 다녀왔어요");
		assertThat(sent.get()).contains("model-x:generateContent");
		assertThat(sent.get()).contains("cclime_official").contains("끌리메 다녀왔어요").contains("poster1");
		assertThat(sent.get()).contains("responseSchema");   // 구조화 출력 강제
	}

	@Test
	void 브랜드_소개가_있으면_요청_바디에_실린다() {
		// 이름 충돌 방어(동명 타업종 판별)의 실질 근거 — 스펙 §4-6 "업종" 컨텍스트
		AtomicReference<String> sent = new AtomicReference<>();
		BrandMentionJudge judge = new BrandMentionJudge((path, body) -> {
			sent.set(body);
			return geminiBody("RELEVANT");
		}, true, "model-x");
		judge.judge("cclime_official", "국내 프리미엄 도자기 식기 브랜드", List.of("끌리메"), "poster1", "끌리메 다녀왔어요");
		assertThat(sent.get()).contains("브랜드 소개").contains("국내 프리미엄 도자기 식기 브랜드");
	}

	@Test
	void 캡션이_null이어도_요청은_성립한다() {
		BrandMentionJudge judge = new BrandMentionJudge((p, b) -> geminiBody("UNCERTAIN"), true, "m");
		assertThat(judge.judge("u", null, List.of("t"), "p", null))
				.isEqualTo(BrandMentionJudge.Verdict.UNCERTAIN);
	}

	@Test
	void 예상_밖_판정_문자열은_예외다() {
		BrandMentionJudge judge = new BrandMentionJudge((p, b) -> geminiBody("MAYBE"), true, "m");
		assertThatThrownBy(() -> judge.judge("u", null, List.of("t"), "p", "c"))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void candidates_본문이_없으면_예외() {
		BrandMentionJudge judge = new BrandMentionJudge((p, b) -> "{}", true, "m");
		assertThatThrownBy(() -> judge.judge("u", null, List.of("t"), "p", "c"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("본문 없음");
	}

	@Test
	void text_안_json에_verdict_필드가_없으면_예외() {
		String body = """
				{"candidates":[{"content":{"parts":[{"text":"{}"}]}}]}""";
		BrandMentionJudge judge = new BrandMentionJudge((p, b) -> body, true, "m");
		assertThatThrownBy(() -> judge.judge("u", null, List.of("t"), "p", "c"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("verdict 없음");
	}

	@Test
	void api_키가_비어있으면_호출_없이_불확실로_접는다() {
		// fail-closed: 키 미설정 환경(로컬 등)에서 스윕이 죽지 않고, 판정 불가분은 비노출(UNCERTAIN)
		BrandMentionJudge judge = new BrandMentionJudge((p, b) -> {
			throw new AssertionError("키 없이는 호출하면 안 된다");
		}, false, "m");
		assertThat(judge.judge("u", null, List.of("t"), "p", "c"))
				.isEqualTo(BrandMentionJudge.Verdict.UNCERTAIN);
	}
}
