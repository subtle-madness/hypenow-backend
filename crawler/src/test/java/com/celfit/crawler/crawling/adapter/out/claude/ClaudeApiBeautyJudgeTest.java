package com.celfit.crawler.crawling.adapter.out.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge.ProfileCard;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge.Verdict;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Claude API(SDK) 판정 전송층: 팀 프롬프트 재사용 + 응답 텍스트를 팀 파서로 매핑. */
class ClaudeApiBeautyJudgeTest {

    ObjectMapper om = new ObjectMapper();

    @Test
    void 요청_파라미터에_팀_프롬프트와_모델이_실린다() {
        String prompt = ClaudeCliBeautyJudge.buildPrompt(om,
                List.of(new ProfileCard("user1", "이름", "카테고리", "바이오", List.of("캡션1"))));
        MessageCreateParams params = ClaudeApiBeautyJudge.requestParams("claude-haiku-4-5", prompt);
        assertEquals("claude-haiku-4-5", params.model().asString());
        String text = params.messages().get(0).content().string().orElse("");
        assertTrue(text.contains("INFLUENCER"));
        assertTrue(text.contains("user1"));
        // 2축 출력 분량(스펙 2026-08-23 §2) — 8192에서 상향
        assertEquals(16384L, params.maxTokens());
    }

    @Test
    void 응답_텍스트를_팀_파서로_판정에_매핑한다() {
        Message message = message(
                "[{\"username\":\"user1\",\"beauty\":{\"class\":\"COMPANY\",\"reason\":\"쇼핑몰\"},"
                        + "\"fnb\":{\"class\":\"NONE\",\"reason\":\"F&B 아님\"}}]");
        List<Verdict> verdicts = ClaudeCliBeautyJudge.parse(om, ClaudeApiBeautyJudge.extractText(message));
        assertEquals(1, verdicts.size());
        assertTrue(verdicts.get(0).beauty());
        assertTrue(verdicts.get(0).company());
    }

    @Test
    void 본문_없는_응답은_ApifyException으로_배치_실패_계약을_지킨다() {
        Message empty = Message.builder()
                .id("msg_test")
                .content(List.of())
                .model("claude-haiku-4-5")
                .stopReason(Optional.empty())
                .stopSequence(Optional.empty())
                .stopDetails(Optional.empty())
                .container(Optional.empty())
                .usage(usage())
                .build();
        assertThrows(ApifyException.class, () -> ClaudeApiBeautyJudge.extractText(empty));
    }

    private static Message message(String text) {
        return Message.builder()
                .id("msg_test")
                .content(List.of(ContentBlock.ofText(TextBlock.builder()
                        .text(text)
                        .citations(List.of())
                        .build())))
                .model("claude-haiku-4-5")
                .stopReason(Optional.empty())
                .stopSequence(Optional.empty())
                .stopDetails(Optional.empty())
                .container(Optional.empty())
                .usage(usage())
                .build();
    }

    private static Usage usage() {
        return Usage.builder()
                .inputTokens(1L)
                .outputTokens(1L)
                .cacheCreationInputTokens(0L)
                .cacheReadInputTokens(0L)
                .cacheCreation(Optional.empty())
                .serverToolUse(Optional.empty())
                .serviceTier(Optional.empty())
                .inferenceGeo(Optional.empty())
                .build();
    }
}
