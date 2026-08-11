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
		BrandMentionJudge judge = new BrandMentionJudge((path, body) -> geminiBody("RELEVANT"), "key", "model-x");
		assertThat(judge.judge("cclime_official", List.of("끌리메", "cclime"), "poster1", "끌리메 후기"))
				.isEqualTo(BrandMentionJudge.Verdict.RELEVANT);
	}

	@Test
	void 무관과_불확실도_파싱한다() {
		assertThat(new BrandMentionJudge((p, b) -> geminiBody("IRRELEVANT"), "key", "m")
				.judge("u", List.of("t"), "p", "c")).isEqualTo(BrandMentionJudge.Verdict.IRRELEVANT);
		assertThat(new BrandMentionJudge((p, b) -> geminiBody("UNCERTAIN"), "key", "m")
				.judge("u", List.of("t"), "p", "c")).isEqualTo(BrandMentionJudge.Verdict.UNCERTAIN);
	}

	@Test
	void 요청_경로와_바디에_모델_브랜드_컨텍스트_캡션이_실린다() {
		AtomicReference<String> sent = new AtomicReference<>();
		BrandMentionJudge judge = new BrandMentionJudge((path, body) -> {
			sent.set(path + "\n" + body);
			return geminiBody("RELEVANT");
		}, "key", "model-x");
		judge.judge("cclime_official", List.of("끌리메", "cclime"), "poster1", "끌리메 다녀왔어요");
		assertThat(sent.get()).contains("model-x:generateContent");
		assertThat(sent.get()).contains("cclime_official").contains("끌리메 다녀왔어요").contains("poster1");
		assertThat(sent.get()).contains("responseSchema");   // 구조화 출력 강제
	}

	@Test
	void 캡션이_null이어도_요청은_성립한다() {
		BrandMentionJudge judge = new BrandMentionJudge((p, b) -> geminiBody("UNCERTAIN"), "key", "m");
		assertThat(judge.judge("u", List.of("t"), "p", null))
				.isEqualTo(BrandMentionJudge.Verdict.UNCERTAIN);
	}

	@Test
	void 예상_밖_판정_문자열은_예외다() {
		BrandMentionJudge judge = new BrandMentionJudge((p, b) -> geminiBody("MAYBE"), "key", "m");
		assertThatThrownBy(() -> judge.judge("u", List.of("t"), "p", "c"))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void api_키가_비어있으면_호출_없이_불확실로_접는다() {
		// fail-closed: 키 미설정 환경(로컬 등)에서 스윕이 죽지 않고, 판정 불가분은 비노출(UNCERTAIN)
		BrandMentionJudge judge = new BrandMentionJudge((p, b) -> {
			throw new AssertionError("키 없이는 호출하면 안 된다");
		}, "", "m");
		assertThat(judge.judge("u", List.of("t"), "p", "c"))
				.isEqualTo(BrandMentionJudge.Verdict.UNCERTAIN);
	}
}
