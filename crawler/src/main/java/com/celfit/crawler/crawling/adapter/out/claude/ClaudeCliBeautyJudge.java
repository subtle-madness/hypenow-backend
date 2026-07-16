package com.celfit.crawler.crawling.adapter.out.claude;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 로컬 Claude Code CLI(headless `claude -p`)로 뷰티 판정 — 백엔드가 claude 로그인된
 * 로컬 맥에서 돈다는 전제(구독 포함, 유료 API 없음). PATH에 claude가 있어야 한다.
 */
@Component
public class ClaudeCliBeautyJudge implements BeautyJudge {

    /** 배치 1회(50명) 판정의 상한 — CLI가 응답을 못 만들면 강제 종료하고 배치 실패로 넘긴다. */
    static final int TIMEOUT_SECONDS = 120;

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

    static String buildPrompt(ObjectMapper om, List<ProfileCard> cards) {
        return """
                다음은 인스타그램 계정 프로필 목록(JSON)이다. 각 계정을 셋 중 하나로 분류하라:
                - INFLUENCER: 뷰티(화장품·메이크업·스킨케어·헤어·네일·에스테틱 등) 콘텐츠 중심의 \
                개인 크리에이터·인플루언서
                - COMPANY: 뷰티 브랜드·회사·쇼핑몰·살롱 등 사업자 공식 계정
                - NOT_BEAUTY: 뷰티 콘텐츠 중심이 아닌 계정
                captions는 최근 게시물 캡션 일부다(앞부분만 잘림·빈 배열은 미수집) — bio가 모호하면 \
                캡션의 실제 콘텐츠 주제를 근거로 판정하라.
                출력은 JSON 배열만: [{"username":"...","class":"INFLUENCER|COMPANY|NOT_BEAUTY","reason":"한 줄"}]
                입력의 모든 username에 대해 정확히 한 항목씩. 다른 텍스트 금지.

                """ + om.writeValueAsString(cards);
    }

    static List<Verdict> parse(ObjectMapper om, String output) {
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
            // 3분류 외 값(모델 일탈)은 건너뛴다 — 해당 계정은 미판정 유지, 다음 실행 재시도
            switch (cls) {
                case "INFLUENCER" -> out.add(new Verdict(username, true, false, n.path("reason").asString(null)));
                case "COMPANY" -> out.add(new Verdict(username, true, true, n.path("reason").asString(null)));
                case "NOT_BEAUTY" -> out.add(new Verdict(username, false, false, n.path("reason").asString(null)));
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
