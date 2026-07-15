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
    void 코드펜스로_감싼_JSON_배열을_판정으로_파싱한다() {
        String output = """
                ```json
                [{"username":"a","beauty":true,"reason":"메이크업 계정"},
                 {"username":"b","beauty":false,"reason":"여행 계정"}]
                ```
                """;
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om, output);
        assertThat(v).containsExactly(
                new BeautyJudge.Verdict("a", true, "메이크업 계정"),
                new BeautyJudge.Verdict("b", false, "여행 계정"));
    }

    @Test
    void 펜스_없는_생_JSON도_파싱한다() {
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om,
                "[{\"username\":\"a\",\"beauty\":true,\"reason\":null}]");
        assertThat(v).containsExactly(new BeautyJudge.Verdict("a", true, null));
    }

    @Test
    void username_누락이나_beauty가_불리언이_아닌_항목은_건너뛴다() {
        List<BeautyJudge.Verdict> v = ClaudeCliBeautyJudge.parse(om, """
                [{"beauty":true,"reason":"x"},
                 {"username":"ok","beauty":"yes"},
                 {"username":"good","beauty":false,"reason":"r"}]
                """);
        assertThat(v).containsExactly(new BeautyJudge.Verdict("good", false, "r"));
    }

    @Test
    void 배열이_아니거나_JSON이_아니면_ApifyException() {
        assertThatThrownBy(() -> ClaudeCliBeautyJudge.parse(om, "{\"oops\":1}"))
                .isInstanceOf(ApifyException.class);
        assertThatThrownBy(() -> ClaudeCliBeautyJudge.parse(om, "죄송합니다, 판정할 수 없습니다."))
                .isInstanceOf(ApifyException.class);
    }

    @Test
    void 프롬프트에_카드_JSON과_출력_형식_지시가_들어간다() {
        String p = ClaudeCliBeautyJudge.buildPrompt(om,
                List.of(new BeautyJudge.ProfileCard("u1", "이름", "Beauty", "bio")));
        assertThat(p).contains("\"username\":\"u1\"").contains("JSON 배열만");
    }
}
