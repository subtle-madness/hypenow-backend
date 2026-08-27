package com.celfit.crawler.crawling.adapter.out.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge;
import com.celfit.crawler.crawling.domain.BeautyClass;
import com.celfit.crawler.crawling.domain.CategoryClass;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ClaudeCliBeautyJudgeTest {

    ObjectMapper om = new ObjectMapper();

    @Test
    void 코드펜스로_감싼_5분류_JSON_배열을_판정으로_파싱한다() {
        String output = """
                ```json
                [{"username":"a","beauty":{"class":"INFLUENCER","reason":"메이크업 크리에이터"},"fnb":{"class":"NONE","reason":"F&B 아님"}},
                 {"username":"b","beauty":{"class":"COMPANY","reason":"화장품 브랜드 공식몰"},"fnb":{"class":"NONE","reason":"F&B 아님"}},
                 {"username":"c","beauty":{"class":"BEAUTY_SERVICE","reason":"피부과 시술 홍보 계정"},"fnb":{"class":"NONE","reason":"F&B 아님"}},
                 {"username":"d","beauty":{"class":"NOT_BEAUTY","reason":"여행 계정"},"fnb":{"class":"NONE","reason":"F&B 아님"}},
                 {"username":"e","beauty":{"class":"FOREIGN_INFLUENCER","reason":"영어 뷰티 콘텐츠"},"fnb":{"class":"NONE","reason":"F&B 아님"}}]
                ```
                """;
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om, output);
        assertThat(v).containsExactly(
                new BeautyJudge.Verdict("a", BeautyClass.INFLUENCER, "메이크업 크리에이터", null,
                        CategoryClass.NONE, "F&B 아님", null, null, null, null),
                new BeautyJudge.Verdict("b", BeautyClass.COMPANY, "화장품 브랜드 공식몰", null,
                        CategoryClass.NONE, "F&B 아님", null, null, null, null),
                new BeautyJudge.Verdict("c", BeautyClass.BEAUTY_SERVICE, "피부과 시술 홍보 계정", null,
                        CategoryClass.NONE, "F&B 아님", null, null, null, null),
                new BeautyJudge.Verdict("d", BeautyClass.NOT_BEAUTY, "여행 계정", null,
                        CategoryClass.NONE, "F&B 아님", null, null, null, null),
                new BeautyJudge.Verdict("e", BeautyClass.FOREIGN_INFLUENCER, "영어 뷰티 콘텐츠", null,
                        CategoryClass.NONE, "F&B 아님", null, null, null, null));
    }

    @Test
    void 이축_JSON을_판정으로_파싱한다() {
        String out = """
                [{"username":"kim","beauty":{"reason":"뷰티 리뷰","basis":"CAPTION","class":"INFLUENCER"},
                  "fnb":{"reason":"레시피 다수","basis":"CAPTION","class":"INFLUENCER"}}]""";
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om, out);
        assertThat(v).hasSize(1);
        assertThat(v.get(0).beautyClass()).isEqualTo(BeautyClass.INFLUENCER);
        assertThat(v.get(0).fnbClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(v.get(0).fnbReason()).isEqualTo("레시피 다수");
        assertThat(v.get(0).fnbBasis()).isEqualTo("CAPTION");
    }

    @Test
    void 한_축이_무효여도_다른_축_판정은_살린다() {
        String out = """
                [{"username":"kim","beauty":{"reason":"r","basis":"BIO","class":"뭔가이상한값"},
                  "fnb":{"reason":"r2","basis":"BIO","class":"NONE"}}]""";
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om, out);
        assertThat(v).hasSize(1);
        assertThat(v.get(0).beautyClass()).isNull();
        assertThat(v.get(0).fnbClass()).isEqualTo(CategoryClass.NONE);
    }

    @Test
    void 양축_모두_무효면_항목을_건너뛴다() {
        String out = """
                [{"username":"kim","beauty":{"class":"X"},"fnb":{"class":"Y"}}]""";
        assertThat(ClaudeCliBeautyJudge.parse(om, out)).isEmpty();
    }

    @Test
    void 프롬프트에_두_축_분류와_출력_형식이_들어간다() {
        String p = ClaudeCliBeautyJudge.buildPrompt(om, List.of(
                new BeautyJudge.ProfileCard("u", "이름", "cat", "bio", List.of())));
        assertThat(p).contains("beauty");
        assertThat(p).contains("fnb");
        assertThat(p).contains("BEAUTY_SERVICE");   // 뷰티 축 어휘
        // F&B 축 어휘(매장) — BEAUTY_SERVICE의 부분 문자열로 우연히 통과하지 않도록 경계까지 함께 본다
        assertThat(p).contains("- SERVICE: 매장");
        assertThat(p).contains("|SERVICE|NONE");
        assertThat(p).contains("NONE");
        assertThat(p).contains("세 축은 독립");
    }

    @Test
    void 삼축_JSON을_파싱한다() {
        String out = """
                [{"username":"a",
                  "beauty":{"reason":"뷰티 리뷰","basis":"CAPTION","class":"INFLUENCER"},
                  "fnb":{"reason":"레시피","basis":"CAPTION","class":"INFLUENCER"},
                  "home_living":{"reason":"집꾸미기 콘텐츠","basis":"CAPTION","class":"INFLUENCER"}}]
                """;
        var verdicts = ClaudeCliBeautyJudge.parse(om, out);
        assertThat(verdicts).hasSize(1);
        assertThat(verdicts.getFirst().homeLivingClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(verdicts.getFirst().homeLivingReason()).isEqualTo("집꾸미기 콘텐츠");
        assertThat(verdicts.getFirst().homeLivingBasis()).isEqualTo("CAPTION");
    }

    @Test
    void 홈리빙_축이_누락되면_그_축만_null이다() {
        String out = """
                [{"username":"a",
                  "beauty":{"reason":"뷰티","basis":"BIO","class":"INFLUENCER"},
                  "fnb":{"reason":"아님","basis":"BIO","class":"NONE"}}]
                """;
        var verdicts = ClaudeCliBeautyJudge.parse(om, out);
        assertThat(verdicts).hasSize(1);
        assertThat(verdicts.getFirst().beautyClass()).isEqualTo(BeautyClass.INFLUENCER);
        assertThat(verdicts.getFirst().homeLivingClass()).isNull();
    }

    @Test
    void 세_축_모두_무효면_건너뛴다() {
        String out = """
                [{"username":"a",
                  "beauty":{"class":"?"},"fnb":{"class":"?"},"home_living":{"class":"?"}}]
                """;
        assertThat(ClaudeCliBeautyJudge.parse(om, out)).isEmpty();
    }

    @Test
    void 프롬프트에_홈리빙_축_지시가_들어간다() {
        String p = ClaudeCliBeautyJudge.buildPrompt(om, List.of(
                new BeautyJudge.ProfileCard("a", "이름", "카테고리", "bio", List.of())));
        assertThat(p).contains("home_living");
        assertThat(p).contains("집꾸미기");
        // ①+② 경계(스펙 2026-08-27) — 일상·가족 계정은 NONE이라는 배제 규칙까지 프롬프트에 실려야 한다
        assertThat(p).contains("집이 배경으로만 등장");
    }

    @Test
    void Verdict의_파생_boolean은_BeautyClass_규칙을_따른다() {
        assertThat(verdict(BeautyClass.INFLUENCER).beauty()).isTrue();
        assertThat(verdict(BeautyClass.COMPANY).company()).isTrue();
        assertThat(verdict(BeautyClass.BEAUTY_SERVICE).beauty()).isFalse();
        assertThat(verdict(BeautyClass.FOREIGN_INFLUENCER).beauty()).isFalse();
        assertThat(verdict(BeautyClass.NOT_BEAUTY).beauty()).isFalse();
        // 뷰티 축 무판정(모델 응답 무효·누락) — 파생 boolean은 false로 접힌다(NPE 아님)
        assertThat(verdict(null).beauty()).isFalse();
        assertThat(verdict(null).company()).isFalse();
    }

    private static BeautyJudge.Verdict verdict(BeautyClass cls) {
        return new BeautyJudge.Verdict("a", cls, null, null, null, null, null, null, null, null);
    }

    @Test
    void 펜스_없는_생_JSON도_파싱한다() {
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om,
                "[{\"username\":\"a\",\"beauty\":{\"class\":\"INFLUENCER\",\"reason\":null},"
                        + "\"fnb\":{\"class\":\"NONE\",\"reason\":null}}]");
        assertThat(v).containsExactly(new BeautyJudge.Verdict("a", BeautyClass.INFLUENCER, null, null,
                CategoryClass.NONE, null, null, null, null, null));
    }

    @Test
    void username_누락이나_class가_5분류가_아닌_항목은_건너뛴다() {
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om, """
                [{"beauty":{"class":"INFLUENCER","reason":"x"},"fnb":{"class":"NONE"}},
                 {"username":"ok","beauty":{"class":"BEAUTY"},"fnb":{"class":"FOOD"}},
                 {"username":"legacy","beauty":true},
                 {"username":"good","beauty":{"class":"NOT_BEAUTY","reason":"r"},"fnb":{"class":"NONE","reason":"r2"}}]
                """);
        assertThat(v).containsExactly(new BeautyJudge.Verdict("good", BeautyClass.NOT_BEAUTY, "r", null,
                CategoryClass.NONE, "r2", null, null, null, null));
    }

    @Test
    void 배열이_아니거나_JSON이_아니면_ApifyException() {
        assertThatThrownBy(() -> ClaudeCliBeautyJudge.parse(om, "{\"oops\":1}"))
                .isInstanceOf(ApifyException.class);
        assertThatThrownBy(() -> ClaudeCliBeautyJudge.parse(om, "죄송합니다, 판정할 수 없습니다."))
                .isInstanceOf(ApifyException.class);
    }

    @Test
    void 프롬프트에_판정_목적과_카드_JSON과_5분류_출력_형식_지시가_들어간다() {
        String p = ClaudeCliBeautyJudge.buildPrompt(om,
                List.of(new BeautyJudge.ProfileCard("u1", "이름", "Beauty", "bio", List.of("입술 보습 꿀템"))));
        assertThat(p).contains("\"username\":\"u1\"").contains("입술 보습 꿀템").contains("JSON 배열만")
                .contains("INFLUENCER").contains("COMPANY").contains("BEAUTY_SERVICE").contains("NOT_BEAUTY")
                .contains("FOREIGN_INFLUENCER").contains("한국어")
                .contains("시딩·협찬").contains("피부과").contains("captions는 최근 게시물 캡션");
    }

    @Test
    void basis를_파싱한다() {
        String output = """
                [{"username":"a","beauty":{"reason":"뷰티 캡션 다수","basis":"CAPTION","class":"INFLUENCER"},
                  "fnb":{"reason":"F&B 아님","basis":"CAPTION","class":"NONE"}}]""";

        var verdicts = ClaudeCliBeautyJudge.parse(om, output);

        assertThat(verdicts).singleElement().satisfies(v -> {
            assertThat(v.username()).isEqualTo("a");
            assertThat(v.basis()).isEqualTo("CAPTION");
            assertThat(v.beautyClass()).isEqualTo(BeautyClass.INFLUENCER);
        });
    }

    @Test
    void 알_수_없는_basis는_null로_두고_판정은_살린다() {
        String output = """
                [{"username":"a","beauty":{"reason":"이유","basis":"VIBES","class":"INFLUENCER"},
                  "fnb":{"reason":"이유","basis":"VIBES","class":"NONE"}},
                 {"username":"b","beauty":{"reason":"이유","class":"NOT_BEAUTY"},
                  "fnb":{"reason":"이유","class":"NONE"}}]""";

        var verdicts = ClaudeCliBeautyJudge.parse(om, output);

        assertThat(verdicts).hasSize(2);
        assertThat(verdicts.get(0).basis()).isNull();
        assertThat(verdicts.get(0).fnbBasis()).isNull();
        assertThat(verdicts.get(1).basis()).isNull();
        assertThat(verdicts.get(1).fnbBasis()).isNull();
    }

    @Test
    void 프롬프트가_category를_미검증_필드로_명시한다() {
        String prompt = ClaudeCliBeautyJudge.buildPrompt(om, List.of(
                new BeautyJudge.ProfileCard("a", "이름", "Beauty, cosmetic & personal care", "bio", List.of())));

        assertThat(prompt).contains("미검증 자기신고 필드");
        assertThat(prompt).contains("CATEGORY_ONLY");
    }

    @Test
    void 프롬프트는_판정_기준이_서술_언어이지_제품_주제_국적이_아님을_명시한다() {
        // v4(07-30): 한국 화장품을 외국어로 리뷰하는 계정이 INFLUENCER로 오분류되던 결함
        // (post-v3 표본 33/33 일본 계정) 대응 — 국적과 서술 언어를 분리하는 규칙이 프롬프트에
        // 들어가는지 검증한다.
        String p = ClaudeCliBeautyJudge.buildPrompt(om,
                List.of(new BeautyJudge.ProfileCard("u1", "이름", "Beauty", "bio", List.of())));
        assertThat(p).contains("다루는 제품·주제의 국적이 아니다")
                .contains("FOREIGN_INFLUENCER")
                .contains("INFLUENCER").contains("COMPANY").contains("BEAUTY_SERVICE").contains("NOT_BEAUTY");
    }
}
