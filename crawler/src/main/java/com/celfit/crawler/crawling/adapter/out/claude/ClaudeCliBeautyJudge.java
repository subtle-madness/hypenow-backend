package com.celfit.crawler.crawling.adapter.out.claude;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge;
import com.celfit.crawler.crawling.domain.BeautyClass;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 로컬 Claude Code CLI(headless `claude -p`)로 뷰티 판정 — 백엔드가 claude 로그인된
 * 로컬 맥에서 돈다는 전제(구독 포함, 유료 API 없음). PATH에 claude가 있어야 한다.
 * 기본 구현은 Gemini(GeminiBeautyJudge, 07-18 확정) — `crawler.beauty.judge=claude-cli`로 롤백.
 * buildPrompt/parse는 Gemini 어댑터가 재사용한다(판정 프롬프트·매핑 단일 원천).
 */
@Component
@ConditionalOnProperty(name = "crawler.beauty.judge", havingValue = "claude-cli")
public class ClaudeCliBeautyJudge implements BeautyJudge {

    /**
     * 배치 1회(50명) 판정의 상한 — CLI가 응답을 못 만들면 강제 종료하고 배치 실패로 넘긴다.
     * 캡션 포함(계정당 최근 5개×100자)으로 프롬프트가 커진 뒤 120s는 간헐 타임아웃(2026-07-16 실측) — 240s로 상향.
     */
    static final int TIMEOUT_SECONDS = 240;

    private final ObjectMapper om;

    public ClaudeCliBeautyJudge(ObjectMapper om) {
        this.om = om;
    }

    @Override
    public List<Verdict> judge(List<ProfileCard> cards) {
        return parse(om, run(buildPrompt(om, cards)));
    }

    private String run(String prompt) {
        try {
            Process p = new ProcessBuilder("claude", "-p", "--model", "haiku", "--output-format", "text")
                    .start();
            try (OutputStream in = p.getOutputStream()) {
                in.write(prompt.getBytes(StandardCharsets.UTF_8));
            }
            // stdout·stderr를 대기와 동시에 드레인 — 파이프 버퍼 크기와 무관하게 교착이 없다
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread outDrain = Thread.startVirtualThread(() -> drain(p.getInputStream(), stdout));
            Thread errDrain = Thread.startVirtualThread(() -> drain(p.getErrorStream(), stderr));
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new ApifyException("claude CLI 타임아웃(" + TIMEOUT_SECONDS + "s)");
            }
            outDrain.join();
            errDrain.join();
            if (p.exitValue() != 0) {
                throw new ApifyException("claude CLI 종료코드 " + p.exitValue() + ": "
                        + stderr.toString(StandardCharsets.UTF_8));
            }
            return stdout.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApifyException("claude CLI 실행 실패(로컬 claude 설치·로그인 필요): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApifyException("claude CLI 대기 중단", e);
        }
    }

    private static void drain(java.io.InputStream from, ByteArrayOutputStream to) {
        try (from) {
            from.transferTo(to);
        } catch (IOException ignored) {
            // 프로세스 강제 종료 시 스트림이 닫히며 나는 예외 — 드레인 목적상 무시
        }
    }

    public static String buildPrompt(ObjectMapper om, List<ProfileCard> cards) {
        return """
                너는 뷰티 마케팅 리스트업 서비스의 분류기다. 목적: 뷰티 제품(스킨케어·메이크업·향수·\
                헤어/바디케어 제품 등)을 시딩·협찬·광고할 인플루언서와, 그런 인플루언서를 필요로 하는 \
                뷰티 제품 회사를 찾는 것.
                다음 인스타그램 계정 프로필 목록(JSON)의 각 계정을 넷 중 하나로 분류하라:
                - INFLUENCER: 뷰티 제품 콘텐츠 중심의 개인 크리에이터. 광고·협찬 게시물만이 아니라 \
                오가닉 뷰티 콘텐츠를 올리는 개인도 포함.
                - COMPANY: 뷰티 제품을 제작·판매하는 회사(브랜드·쇼핑몰) 공식 계정
                - BEAUTY_SERVICE: 뷰티 영역이지만 시술·서비스 중심 — 피부과·성형외과·에스테틱·헤어샵/\
                미용실·네일샵·왁싱·속눈썹·반영구 등 시술을 파는 업체, 그리고 헤어 디자이너·네일 아티스트·\
                시술 후기 위주 계정 같은 시술·서비스 중심 개인
                - NOT_BEAUTY: 뷰티 콘텐츠 중심이 아닌 계정
                경계 규칙: 시술 업체가 자체 제품도 팔면 콘텐츠 주력 기준으로 — 시술·매장 홍보 중심이면 \
                BEAUTY_SERVICE, 제품 판매 중심이면 COMPANY.
                captions는 최근 게시물 캡션 일부다(앞부분만 잘림·빈 배열은 미수집) — bio가 모호하면 \
                캡션의 실제 콘텐츠 주제를 근거로 판정하라.
                출력은 JSON 배열만: [{"username":"...","class":"INFLUENCER|COMPANY|BEAUTY_SERVICE|NOT_BEAUTY","reason":"한 줄"}]
                입력의 모든 username에 대해 정확히 한 항목씩. 다른 텍스트 금지.

                """ + om.writeValueAsString(cards);
    }

    public static List<Verdict> parse(ObjectMapper om, String output) {
        String json = stripFences(output);
        JsonNode root;
        try {
            root = om.readTree(json);
        } catch (JacksonException e) {
            throw new ApifyException("판정 응답 파싱 실패: " + e.getMessage(), e);
        }
        if (!root.isArray()) throw new ApifyException("판정 응답이 JSON 배열이 아님");
        List<Verdict> out = new ArrayList<>();
        for (JsonNode n : root) {
            String username = n.path("username").asString(null);
            String cls = n.path("class").asString(null);
            if (username == null || username.isBlank() || cls == null) continue;
            // 4분류 외 값(모델 일탈)은 건너뛴다 — 해당 계정은 미판정 유지, 다음 실행 재시도
            switch (cls) {
                case "INFLUENCER" -> out.add(new Verdict(username, BeautyClass.INFLUENCER, n.path("reason").asString(null)));
                case "COMPANY" -> out.add(new Verdict(username, BeautyClass.COMPANY, n.path("reason").asString(null)));
                case "BEAUTY_SERVICE" -> out.add(new Verdict(username, BeautyClass.BEAUTY_SERVICE, n.path("reason").asString(null)));
                case "NOT_BEAUTY" -> out.add(new Verdict(username, BeautyClass.NOT_BEAUTY, n.path("reason").asString(null)));
                default -> { }
            }
        }
        return out;
    }

    /** 모델이 지시를 어기고 ```json 펜스로 감싼 경우 벗긴다. */
    static String stripFences(String s) {
        String t = s.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl < 0 ? "" : t.substring(nl + 1);
            int end = t.lastIndexOf("```");
            if (end >= 0) t = t.substring(0, end);
        }
        return t.strip();
    }
}
