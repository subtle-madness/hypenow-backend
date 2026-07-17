package com.celfit.crawler.crawling.adapter.out.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ClaudeCliBeautyJudgeTest {

    ObjectMapper om = new ObjectMapper();

    @Test
    void 코드펜스로_감싼_3분류_JSON_배열을_판정으로_파싱한다() {
        String output = """
                ```json
                [{"username":"a","class":"INFLUENCER","reason":"메이크업 크리에이터"},
                 {"username":"b","class":"COMPANY","reason":"화장품 브랜드 공식몰"},
                 {"username":"c","class":"NOT_BEAUTY","reason":"여행 계정"}]
                ```
                """;
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om, output);
        assertThat(v).containsExactly(
                new BeautyJudge.Verdict("a", true, false, "메이크업 크리에이터"),
                new BeautyJudge.Verdict("b", true, true, "화장품 브랜드 공식몰"),
                new BeautyJudge.Verdict("c", false, false, "여행 계정"));
    }

    @Test
    void 펜스_없는_생_JSON도_파싱한다() {
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om,
                "[{\"username\":\"a\",\"class\":\"INFLUENCER\",\"reason\":null}]");
        assertThat(v).containsExactly(new BeautyJudge.Verdict("a", true, false, null));
    }

    @Test
    void username_누락이나_class가_3분류가_아닌_항목은_건너뛴다() {
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om, """
                [{"class":"INFLUENCER","reason":"x"},
                 {"username":"ok","class":"BEAUTY"},
                 {"username":"legacy","beauty":true},
                 {"username":"good","class":"NOT_BEAUTY","reason":"r"}]
                """);
        assertThat(v).containsExactly(new BeautyJudge.Verdict("good", false, false, "r"));
    }

    @Test
    void 배열이_아니거나_JSON이_아니면_ApifyException() {
        assertThatThrownBy(() -> ClaudeCliBeautyJudge.parse(om, "{\"oops\":1}"))
                .isInstanceOf(ApifyException.class);
        assertThatThrownBy(() -> ClaudeCliBeautyJudge.parse(om, "죄송합니다, 판정할 수 없습니다."))
                .isInstanceOf(ApifyException.class);
    }

    @Test
    void 프롬프트에_카드_JSON과_3분류_출력_형식_지시가_들어간다() {
        String p = ClaudeCliBeautyJudge.buildPrompt(om,
                List.of(new BeautyJudge.ProfileCard("u1", "이름", "Beauty", "bio", List.of("입술 보습 꿀템"))));
        assertThat(p).contains("\"username\":\"u1\"").contains("입술 보습 꿀템").contains("JSON 배열만")
                .contains("INFLUENCER").contains("COMPANY").contains("NOT_BEAUTY")
                .contains("captions는 최근 게시물 캡션");
    }
}
