package com.celfit.crawler.crawling.adapter.out.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.crawler.crawling.adapter.out.claude.ClaudeCliBeautyJudge;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge.ProfileCard;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Gemini 판정 전송층: 팀 프롬프트 재사용 + responseSchema 배열 출력 + 팀 파서로 매핑. */
class GeminiBeautyJudgeTest {

    ObjectMapper om = new ObjectMapper();

    @Test
    void 요청_본문에_팀_프롬프트와_배열_스키마가_실린다() {
        String prompt = ClaudeCliBeautyJudge.buildPrompt(om,
                List.of(new ProfileCard("user1", "이름", "카테고리", "바이오", List.of("캡션1"))));
        String body = GeminiBeautyJudge.requestBody(om, prompt);
        JsonNode root = om.readTree(body);
        String text = root.path("contents").get(0).path("parts").get(0).path("text").asString();
        assertTrue(text.contains("INFLUENCER"));
        assertTrue(text.contains("user1"));
        JsonNode gen = root.path("generationConfig");
        assertEquals("array", gen.path("responseSchema").path("type").asString());
        assertEquals("application/json", gen.path("responseMimeType").asString());
        assertEquals(0, gen.path("temperature").asInt());
    }

    @Test
    void 응답_텍스트를_팀_파서로_판정에_매핑한다() {
        String response = """
                {"candidates":[{"content":{"parts":[{"text":
                "[{\\"username\\":\\"user1\\",\\"beauty\\":{\\"class\\":\\"COMPANY\\",\\"reason\\":\\"쇼핑몰\\"},\
                \\"fnb\\":{\\"class\\":\\"NONE\\",\\"reason\\":\\"F&B 아님\\"}}]"}]}}]}""";
        String text = GeminiBeautyJudge.extractText(om, response);
        List<Verdict> verdicts = ClaudeCliBeautyJudge.parse(om, text);
        assertEquals(1, verdicts.size());
        assertTrue(verdicts.get(0).beauty());
        assertTrue(verdicts.get(0).company());
    }

    @Test
    void 본문_없는_응답은_ApifyException으로_배치_실패_계약을_지킨다() {
        assertThrows(ApifyException.class, () -> GeminiBeautyJudge.extractText(om, "{}"));
    }

    @Test
    void 응답_스키마가_프롬프트의_5분류와_basis를_모두_담는다() {
        assertThat(GeminiBeautyJudge.RESPONSE_SCHEMA)
                .contains("FOREIGN_INFLUENCER")
                .contains("CATEGORY_ONLY");
    }

    @Test
    void 응답_스키마가_두_축을_각각_담는다() {
        // 2축 판정(스펙 2026-08-23 §2) — beauty는 BEAUTY_SERVICE·NOT_BEAUTY, fnb는 SERVICE·NONE 어휘
        assertThat(GeminiBeautyJudge.RESPONSE_SCHEMA)
                .contains("\"beauty\"")
                .contains("\"fnb\"")
                .contains("BEAUTY_SERVICE")
                .contains("NOT_BEAUTY")
                .contains("\"SERVICE\"")
                .contains("\"NONE\"");
    }

    @Test
    void 출력_토큰_상한이_3축_분량으로_상향되어_있다() {
        String body = GeminiBeautyJudge.requestBody(om, "prompt");
        assertEquals(24576, om.readTree(body).path("generationConfig").path("maxOutputTokens").asInt());
    }
}
