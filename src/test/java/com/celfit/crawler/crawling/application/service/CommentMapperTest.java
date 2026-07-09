package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CommentMapperTest extends IntegrationTest {

    @Autowired CommentMapper mapper;

    String fixture() throws Exception {
        return new String(getClass().getResourceAsStream("/instagram/comments-response.json").readAllBytes());
    }

    @Test
    void 응답을_스키마호환_댓글맵으로_변환한다() throws Exception {
        var page = mapper.parse(fixture(), "https://www.instagram.com/p/AA/");
        // 픽스처엔 댓글 15개, 단일 페이지(has_next_page=false, end_cursor=null)
        assertThat(page.comments()).hasSize(15);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.endCursor()).isNull();
        var first = page.comments().get(0);
        assertThat(first).containsKeys("postUrl", "ownerUsername", "text", "timestamp");
        assertThat(first.get("postUrl")).isEqualTo("https://www.instagram.com/p/AA/");
        assertThat(first.get("ownerUsername")).isEqualTo("songsariiiii");
        assertThat(first.get("text")).isEqualTo("이정도는 기본아잉교 ❤️");
        // created_at 1779661498(epoch초) → ISO-8601 UTC 문자열
        assertThat(first.get("timestamp")).isEqualTo("2026-05-24T22:24:58.000Z");
    }
}
