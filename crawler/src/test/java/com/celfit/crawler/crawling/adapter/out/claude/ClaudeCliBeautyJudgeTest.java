package com.celfit.crawler.crawling.adapter.out.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge;
import com.celfit.crawler.crawling.domain.BeautyClass;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ClaudeCliBeautyJudgeTest {

    ObjectMapper om = new ObjectMapper();

    @Test
    void 코드펜스로_감싼_5분류_JSON_배열을_판정으로_파싱한다() {
        String output = """
                ```json
                [{"username":"a","class":"INFLUENCER","reason":"메이크업 크리에이터"},
                 {"username":"b","class":"COMPANY","reason":"화장품 브랜드 공식몰"},
                 {"username":"c","class":"BEAUTY_SERVICE","reason":"피부과 시술 홍보 계정"},
                 {"username":"d","class":"NOT_BEAUTY","reason":"여행 계정"},
                 {"username":"e","class":"FOREIGN_INFLUENCER","reason":"영어 뷰티 콘텐츠"}]
                ```
                """;
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om, output);
        assertThat(v).containsExactly(
                new BeautyJudge.Verdict("a", BeautyClass.INFLUENCER, "메이크업 크리에이터", null),
                new BeautyJudge.Verdict("b", BeautyClass.COMPANY, "화장품 브랜드 공식몰", null),
                new BeautyJudge.Verdict("c", BeautyClass.BEAUTY_SERVICE, "피부과 시술 홍보 계정", null),
                new BeautyJudge.Verdict("d", BeautyClass.NOT_BEAUTY, "여행 계정", null),
                new BeautyJudge.Verdict("e", BeautyClass.FOREIGN_INFLUENCER, "영어 뷰티 콘텐츠", null));
    }

    @Test
    void Verdict의_파생_boolean은_BeautyClass_규칙을_따른다() {
        assertThat(new BeautyJudge.Verdict("a", BeautyClass.INFLUENCER, null, null).beauty()).isTrue();
        assertThat(new BeautyJudge.Verdict("a", BeautyClass.COMPANY, null, null).company()).isTrue();
        assertThat(new BeautyJudge.Verdict("a", BeautyClass.BEAUTY_SERVICE, null, null).beauty()).isFalse();
        assertThat(new BeautyJudge.Verdict("a", BeautyClass.FOREIGN_INFLUENCER, null, null).beauty()).isFalse();
        assertThat(new BeautyJudge.Verdict("a", BeautyClass.NOT_BEAUTY, null, null).beauty()).isFalse();
    }

    @Test
    void 펜스_없는_생_JSON도_파싱한다() {
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om,
                "[{\"username\":\"a\",\"class\":\"INFLUENCER\",\"reason\":null}]");
        assertThat(v).containsExactly(new BeautyJudge.Verdict("a", BeautyClass.INFLUENCER, null, null));
    }

    @Test
    void username_누락이나_class가_5분류가_아닌_항목은_건너뛴다() {
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om, """
                [{"class":"INFLUENCER","reason":"x"},
                 {"username":"ok","class":"BEAUTY"},
                 {"username":"legacy","beauty":true},
                 {"username":"good","class":"NOT_BEAUTY","reason":"r"}]
                """);
        assertThat(v).containsExactly(new BeautyJudge.Verdict("good", BeautyClass.NOT_BEAUTY, "r", null));
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
                [{"username":"a","reason":"뷰티 캡션 다수","basis":"CAPTION","class":"INFLUENCER"}]""";

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
                [{"username":"a","reason":"이유","basis":"VIBES","class":"INFLUENCER"},
                 {"username":"b","reason":"이유","class":"NOT_BEAUTY"}]""";

        var verdicts = ClaudeCliBeautyJudge.parse(om, output);

        assertThat(verdicts).hasSize(2);
        assertThat(verdicts.get(0).basis()).isNull();
        assertThat(verdicts.get(1).basis()).isNull();
    }

    @Test
    void 프롬프트가_category를_미검증_필드로_명시한다() {
        String prompt = ClaudeCliBeautyJudge.buildPrompt(om, List.of(
                new BeautyJudge.ProfileCard("a", "이름", "Beauty, cosmetic & personal care", "bio", List.of())));

        assertThat(prompt).contains("미검증 자기신고 필드");
        assertThat(prompt).contains("CATEGORY_ONLY");
    }
}
