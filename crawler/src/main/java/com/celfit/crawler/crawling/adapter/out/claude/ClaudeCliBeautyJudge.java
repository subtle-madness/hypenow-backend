package com.celfit.crawler.crawling.adapter.out.claude;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge;
import com.celfit.crawler.crawling.domain.BeautyClass;
import com.celfit.crawler.crawling.domain.CategoryClass;
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
                너는 인플루언서 마케팅 리스트업 서비스의 분류기다. 각 인스타그램 계정을 세 카테고리 \
                축에서 독립적으로 분류한다: beauty(뷰티 제품 — 스킨케어·메이크업·향수·헤어/바디케어 \
                제품 등), fnb(식품/음료 제품 — 가공식품·음료·건강기능식품·식재료 등), home_living(홈/리빙 \
                — 가구·인테리어 소품·주방/생활용품·홈데코 등). 한 계정이 여러 축에 해당할 수 있다(예: 뷰티 \
                리뷰와 레시피를 함께 올리는 계정).
                목적: 한국 시장에서 각 카테고리 제품을 시딩·협찬·광고할 한국인 인플루언서와, 그런 \
                인플루언서를 필요로 하는 제품 회사를 찾는 것.

                [beauty 축 분류]
                - INFLUENCER: 게시물 캡션·bio를 한국어로 쓰는 뷰티 제품 개인 크리에이터. 광고·협찬 \
                게시물만이 아니라 오가닉 뷰티 콘텐츠를 올리는 개인도 포함.
                - FOREIGN_INFLUENCER: 뷰티 제품 개인 크리에이터지만 글을 한국어로 쓰지 않는 계정
                - COMPANY: 뷰티 제품을 제작·판매하는 회사(브랜드·쇼핑몰) 공식 계정 — 언어 무관
                - BEAUTY_SERVICE: 뷰티 영역이지만 시술·서비스 중심 — 피부과·성형외과·에스테틱·헤어샵/\
                미용실·네일샵·왁싱·속눈썹·반영구 등 시술을 파는 업체, 그리고 헤어 디자이너·네일 아티스트·\
                시술 후기 위주 계정 같은 시술·서비스 중심 개인
                - NOT_BEAUTY: 뷰티 콘텐츠 중심이 아닌 계정

                [fnb 축 분류]
                - INFLUENCER: 캡션·bio를 한국어로 쓰는 F&B 개인 크리에이터 — 요리/레시피, 식품·음료 \
                제품 리뷰, 맛집·카페 탐방 개인 계정 모두 포함(제품 시딩이 가능한 개인).
                - FOREIGN_INFLUENCER: F&B 개인 크리에이터지만 글을 한국어로 쓰지 않는 계정
                - COMPANY: 식품·음료 제품을 제조·판매하는 회사(브랜드·쇼핑몰) 공식 계정 — 언어 무관
                - SERVICE: 매장·서비스 공식 계정 — 식당·카페·베이커리·술집 등 업장 자체의 계정. \
                개인이 매장을 탐방·리뷰하는 계정은 SERVICE가 아니라 INFLUENCER다.
                - NONE: F&B 콘텐츠 중심이 아닌 계정

                [home_living 축 분류]
                - INFLUENCER: 캡션·bio를 한국어로 쓰는 홈/리빙 개인 크리에이터 — 두 부류 모두 포함한다. \
                (1) 리빙 제품(가구·인테리어 소품·주방/생활용품·홈데코) 리뷰·공동구매·추천 계정, \
                (2) 집꾸미기·홈스타일링·살림·정리수납·홈카페 콘텐츠 중심 계정(제품 리뷰가 주업이 \
                아니어도 집·공간·살림이 콘텐츠의 중심이면 포함 — 예: 오늘의집류 집 기록 계정).
                - FOREIGN_INFLUENCER: 홈/리빙 개인 크리에이터지만 글을 한국어로 쓰지 않는 계정
                - COMPANY: 가구·리빙 제품을 제조·판매하는 회사(브랜드·쇼핑몰) 공식 계정 — 언어 무관
                - SERVICE: 서비스 업체 공식 계정 — 인테리어 시공·리모델링·이사·입주청소·정리수납 대행·\
                부동산 등, 그리고 시공 사례·견적 홍보 위주의 서비스 중심 개인
                - NONE: 홈/리빙 콘텐츠 중심이 아닌 계정. 집이 배경으로만 등장하는 일상·가족·육아 \
                계정은 홈/리빙이 아니다 — 콘텐츠의 주제가 집·공간·살림·리빙 제품인지로 판정하라.

                경계 규칙:
                - 세 축은 독립이다 — 한 축의 판정이 다른 축에 영향을 주지 않는다. 어느 쪽도 아니면 \
                beauty=NOT_BEAUTY, fnb=NONE, home_living=NONE이다.
                - 시술 업체가 자체 제품도 팔면 콘텐츠 주력 기준으로 — 시술·매장 홍보 중심이면 \
                BEAUTY_SERVICE, 제품 판매 중심이면 COMPANY. F&B 매장이 자체 제품(밀키트·원두·소스 등)을 \
                온라인 판매해도 같은 기준 — 매장 홍보 중심이면 SERVICE, 제품 판매 중심이면 COMPANY.
                - 한국어 판정은 캡션이 최우선 신호다 — bio가 영어라도 캡션이 주로 한국어면 한국어 \
                콘텐츠(INFLUENCER)로 판정하라(한국 계정이 영어 bio를 쓰는 경우가 흔하다). 반대도 \
                같다 — bio에 한국어가 섞여 있어도 캡션이 주로 외국어면 FOREIGN_INFLUENCER다.
                - 한국어·외국어를 섞어 쓰면 주 오디언스가 한국인지 기준으로 판정하라.
                - 캡션이 빈 배열(미수집)이고 bio만으로 모호하면 이름·bio의 한국어 여부로 판정하라.
                - 판정 기준은 계정이 글을 쓰는 언어이지, 다루는 제품·주제의 국적이 아니다. 한국 \
                브랜드 제품을 리뷰해도, 한국에 거주해도, bio·캡션을 일본어·중국어·영어 등으로 \
                쓰면 FOREIGN_INFLUENCER다(한국 시장 시딩 대상이 아니므로). 예: "韓国コスメ"를 일본어로 \
                리뷰하는 일본 계정 → beauty축 FOREIGN_INFLUENCER.
                - bio·이름이 히라가나·가타카나·한자(중국어)·태국어·키릴 문자 등으로 된 문장이면 강한 \
                외국어 신호다. 단, ヽ( ´ー｀)ノ·ﾟ·・ 같은 장식용 카오모지 문자는 한국 계정도 흔히 \
                쓰므로 신호가 아니다 — 낱글자 장식인지 문장을 이루는지로 구분하라.
                - category는 계정주가 자율 선택한 미검증 자기신고 필드다 — bio·캡션의 실제 내용과 \
                상충하면 실제 내용을 우선하라.
                captions는 최근 게시물 캡션 일부다(앞부분만 잘림·빈 배열은 미수집) — bio가 모호하면 \
                캡션의 실제 콘텐츠 주제를 근거로 판정하라.
                basis는 각 축 판정의 주근거다 — 캡션의 콘텐츠 주제를 근거로 했으면 CAPTION, bio·이름을 \
                근거로 했으면 BIO, 캡션도 bio도 근거가 되지 못해 category만 보고 판단했으면 CATEGORY_ONLY.
                각 축에서 reason(근거)을 먼저 쓰고, 그 근거와 일관된 class를 마지막에 쓰라.
                출력은 JSON 배열만: [{"username":"...",\
                "beauty":{"reason":"한 줄","basis":"CAPTION|BIO|CATEGORY_ONLY","class":"INFLUENCER|FOREIGN_INFLUENCER|COMPANY|BEAUTY_SERVICE|NOT_BEAUTY"},\
                "fnb":{"reason":"한 줄","basis":"CAPTION|BIO|CATEGORY_ONLY","class":"INFLUENCER|FOREIGN_INFLUENCER|COMPANY|SERVICE|NONE"},\
                "home_living":{"reason":"한 줄","basis":"CAPTION|BIO|CATEGORY_ONLY","class":"INFLUENCER|FOREIGN_INFLUENCER|COMPANY|SERVICE|NONE"}}]
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
            if (username == null || username.isBlank()) continue;
            JsonNode b = n.path("beauty");
            JsonNode f = n.path("fnb");
            JsonNode h = n.path("home_living");
            BeautyClass beautyClass = parseBeautyClass(b.path("class").asString(null));
            CategoryClass fnbClass = parseCategoryClass(f.path("class").asString(null));
            CategoryClass homeLivingClass = parseCategoryClass(h.path("class").asString(null));
            // 세 축 모두 무효(모델 일탈)면 건너뛴다 — 해당 계정 전 축 미판정 유지, 다음 실행 재시도.
            // 일부 축만 무효면 그 축만 null — 유효한 축의 판정을 버릴 이유가 없다.
            if (beautyClass == null && fnbClass == null && homeLivingClass == null) continue;
            out.add(new Verdict(username, beautyClass, b.path("reason").asString(null),
                    normalizeBasis(b.path("basis").asString(null)),
                    fnbClass, f.path("reason").asString(null),
                    normalizeBasis(f.path("basis").asString(null)),
                    homeLivingClass, h.path("reason").asString(null),
                    normalizeBasis(h.path("basis").asString(null))));
        }
        return out;
    }

    private static BeautyClass parseBeautyClass(String cls) {
        if (cls == null) return null;
        return switch (cls) {
            case "INFLUENCER" -> BeautyClass.INFLUENCER;
            case "FOREIGN_INFLUENCER" -> BeautyClass.FOREIGN_INFLUENCER;
            case "COMPANY" -> BeautyClass.COMPANY;
            case "BEAUTY_SERVICE" -> BeautyClass.BEAUTY_SERVICE;
            case "NOT_BEAUTY" -> BeautyClass.NOT_BEAUTY;
            default -> null;
        };
    }

    private static CategoryClass parseCategoryClass(String cls) {
        if (cls == null) return null;
        return switch (cls) {
            case "INFLUENCER" -> CategoryClass.INFLUENCER;
            case "FOREIGN_INFLUENCER" -> CategoryClass.FOREIGN_INFLUENCER;
            case "COMPANY" -> CategoryClass.COMPANY;
            case "SERVICE" -> CategoryClass.SERVICE;
            case "NONE" -> CategoryClass.NONE;
            default -> null;  // 5분류 외 값(모델 일탈) — 해당 축 미판정 유지
        };
    }

    /**
     * class와 달리 basis는 알 수 없는 값이어도 판정을 버릴 이유가 없다 — 근거 표시만 비우고 판정은 살린다.
     */
    private static String normalizeBasis(String basis) {
        if (basis == null) return null;
        return switch (basis) {
            case "CAPTION", "BIO", "CATEGORY_ONLY" -> basis;
            default -> null;
        };
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
