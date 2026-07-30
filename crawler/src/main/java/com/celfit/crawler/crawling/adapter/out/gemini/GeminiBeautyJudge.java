package com.celfit.crawler.crawling.adapter.out.gemini;

import com.celfit.crawler.crawling.adapter.out.claude.ClaudeCliBeautyJudge;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 뷰티 판정 Gemini 구현 (2026-07-18 확정 — 전 분석 축 gemini-3.1-flash-lite 통일).
 * 프롬프트·파서는 팀 것(ClaudeCliBeautyJudge) 재사용 — 이 클래스는 전송(REST + responseSchema)만.
 * 실패 계약은 포트 그대로: 호출 오류·타임아웃·파싱 불가 → ApifyException (호출자가 배치 단위 격리).
 * 무료 티어 키(GEMINI_API_KEY) — analytics 잡과 동시 실행 시 분당 15콜 합산 초과 주의.
 * 기본은 claude-api(ClaudeApiBeautyJudge) — 이 구현은 `crawler.beauty.judge=gemini` 명시 시의 롤백 경로.
 */
@Component
@ConditionalOnProperty(name = "crawler.beauty.judge", havingValue = "gemini")
public class GeminiBeautyJudge implements BeautyJudge {

    private static final Logger log = LoggerFactory.getLogger(GeminiBeautyJudge.class);
    private static final int MAX_ATTEMPTS = 6;
    private static final long RETRY_BASE_MILLIS = 15_000;
    private static final long PACE_MILLIS = 4_000; // 무료 티어 15 RPM — 청크(50계정) 간 최소 간격

    static final String RESPONSE_SCHEMA = """
            {"type":"array","items":{"type":"object","properties":{
              "username":{"type":"string"},
              "reason":{"type":"string"},
              "basis":{"type":"string","enum":["CAPTION","BIO","CATEGORY_ONLY"]},
              "class":{"type":"string","enum":["INFLUENCER","FOREIGN_INFLUENCER","COMPANY","BEAUTY_SERVICE","NOT_BEAUTY"]}},
             "required":["username","reason","basis","class"]}}""";

    private final ObjectMapper om;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private long nextAllowedAt;

    public GeminiBeautyJudge(ObjectMapper om,
            @Value("${crawler.beauty.gemini-model:gemini-3.1-flash-lite}") String model) {
        this.om = om;
        this.model = model;
    }

    @Override
    public List<Verdict> judge(List<ProfileCard> cards) {
        String prompt = ClaudeCliBeautyJudge.buildPrompt(om, cards);
        String responseBody = call(requestBody(om, prompt));
        return ClaudeCliBeautyJudge.parse(om, extractText(om, responseBody));
    }

    static String requestBody(ObjectMapper om, String prompt) {
        ObjectNode root = om.createObjectNode();
        root.putArray("contents").addObject().put("role", "user")
                .putArray("parts").addObject().put("text", prompt);
        ObjectNode gen = root.putObject("generationConfig");
        gen.put("temperature", 0);
        gen.put("responseMimeType", "application/json");
        gen.set("responseSchema", om.readTree(RESPONSE_SCHEMA));
        gen.put("maxOutputTokens", 8192);
        return om.writeValueAsString(root);
    }

    static String extractText(ObjectMapper om, String responseBody) {
        JsonNode text = om.readTree(responseBody)
                .path("candidates").path(0).path("content").path("parts").path(0).path("text");
        if (text.isMissingNode()) {
            throw new ApifyException("Gemini 판정 응답에 본문 없음");
        }
        return text.asString();
    }

    private String call(String body) {
        String key = System.getenv("GEMINI_API_KEY");
        if (key == null || key.isBlank()) {
            throw new ApifyException("GEMINI_API_KEY 미설정 — 셸 export 필요 (.env는 JVM에 자동 로드되지 않음)");
        }
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            pace();
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(120))
                        .header("Content-Type", "application/json")
                        .header("x-goog-api-key", key)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() >= 200 && res.statusCode() < 300) {
                    return res.body();
                }
                if ((res.statusCode() == 429 || res.statusCode() == 500 || res.statusCode() == 503)
                        && attempt < MAX_ATTEMPTS) {
                    long wait = RETRY_BASE_MILLIS * attempt;
                    log.warn("gemini 판정 HTTP {} — {}ms 후 재시도 ({}/{})", res.statusCode(), wait,
                            attempt, MAX_ATTEMPTS);
                    sleep(wait);
                    continue;
                }
                // 일 한도 포함 — 판정 배치는 기존 계약대로 배치 실패 처리, 다음 실행에서 재대상
                throw new ApifyException("Gemini 판정 HTTP " + res.statusCode());
            } catch (IOException e) {
                throw new ApifyException("Gemini 판정 호출 실패: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApifyException("Gemini 판정 대기 중단", e);
            }
        }
        throw new ApifyException("Gemini 판정 재시도 소진");
    }

    private synchronized void pace() {
        long now = System.currentTimeMillis();
        long wait = nextAllowedAt - now;
        if (wait > 0) {
            sleep(wait);
        }
        nextAllowedAt = Math.max(now, nextAllowedAt) + PACE_MILLIS;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApifyException("Gemini 판정 대기 중단", e);
        }
    }
}
