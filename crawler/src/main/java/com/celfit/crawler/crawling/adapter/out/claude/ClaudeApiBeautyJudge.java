package com.celfit.crawler.crawling.adapter.out.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 뷰티 판정 Anthropic SDK 구현 — 클라우드 배포용(로컬 Claude CLI 대체, 스펙 2026-07-19).
 * 프롬프트·파서는 팀 것(ClaudeCliBeautyJudge) 재사용 — 이 클래스는 전송만.
 * 인증은 ClaudeClientFactory(구독 authToken 우선)를 판정 시점에 지연 생성 —
 * 자격증명 부재가 앱 기동을 막지 않고 배치 실패(ApifyException)로 격리된다.
 * 재시도는 SDK 내장(429/5xx 기본 2회)으로 충분해 커스텀 재시도를 두지 않는다.
 */
@Component
@ConditionalOnProperty(name = "crawler.beauty.judge", havingValue = "claude-api")
public class ClaudeApiBeautyJudge implements BeautyJudge {

    private static final long MAX_TOKENS = 8192L;

    private final ObjectMapper om;
    private final String model;
    private AnthropicClient client;

    public ClaudeApiBeautyJudge(ObjectMapper om,
            @Value("${crawler.beauty.claude-model:claude-haiku-4-5}") String model) {
        this.om = om;
        this.model = model;
    }

    @Override
    public List<Verdict> judge(List<ProfileCard> cards) {
        String prompt = ClaudeCliBeautyJudge.buildPrompt(om, cards);
        Message message;
        try {
            message = client().messages().create(requestParams(model, prompt));
        } catch (AnthropicException e) {
            throw new ApifyException("Claude 판정 호출 실패: " + e.getMessage(), e);
        }
        return ClaudeCliBeautyJudge.parse(om, extractText(message));
    }

    static MessageCreateParams requestParams(String model, String prompt) {
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .addUserMessage(prompt)
                .build();
    }

    static String extractText(Message message) {
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining());
        if (text.isBlank()) {
            throw new ApifyException("Claude 판정 응답에 본문 없음");
        }
        return text;
    }

    private synchronized AnthropicClient client() {
        if (client == null) {
            try {
                client = ClaudeClientFactory.fromEnv();
            } catch (IllegalStateException e) {
                throw new ApifyException(e.getMessage(), e);
            }
        }
        return client;
    }
}
