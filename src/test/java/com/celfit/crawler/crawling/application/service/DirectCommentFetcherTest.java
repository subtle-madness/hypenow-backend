package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.CommentSource;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Transactional
class DirectCommentFetcherTest extends IntegrationTest {

    @Autowired CrawlExecutor executor;
    @Autowired CommentMapper mapper;   // tools.jackson ObjectMapper가 주입된 빈
    @Autowired ObjectMapper om;

    static String res(String p) throws Exception {
        return new String(DirectCommentFetcherTest.class.getResourceAsStream(p).readAllBytes());
    }

    // Fake 웹클라이언트: 페이지 HTML과 graphql 응답들을 순서대로 반환
    static class FakeWeb implements InstagramWebClient {
        String html; List<String> graphql; int i = 0; int getStatus = 200; int postStatus = 200;
        String lastBody; int getCount = 0; int postCount = 0;
        public Response get(String url) { getCount++; return new Response(getStatus, html, Map.of()); }
        public Response post(String url, String body, Map<String, String> h) {
            postCount++; lastBody = body;
            if (postStatus >= 300) return new Response(postStatus, "blocked", Map.of());
            return new Response(200, graphql.get(Math.min(i++, graphql.size() - 1)), Map.of());
        }
    }

    DirectCommentFetcher fetcher(FakeWeb web) {
        return new DirectCommentFetcher(web, executor, mapper, Duration.ZERO, "DOC", "FRIENDLY", om);
    }

    @Test
    void source는_DIRECT다() {
        assertThat(fetcher(new FakeWeb()).source()).isEqualTo(CommentSource.DIRECT);
    }

    @Test
    void 단일페이지_응답의_댓글을_전부_수집한다() throws Exception {
        var web = new FakeWeb();
        web.html = res("/instagram/post-page.html");
        web.graphql = List.of(res("/instagram/comments-response.json"));  // hasNext=false
        var ex = fetcher(web).fetch(List.of("DYtaeT4TPYu"), 50, TriggerType.MANUAL);
        assertThat(ex.items()).hasSize(15);
        assertThat(ex.items().get(0)).containsEntry("ownerUsername", "songsariiiii");
    }

    @Test
    void limit을_초과하면_잘라낸다() throws Exception {
        var web = new FakeWeb();
        web.html = res("/instagram/post-page.html");
        web.graphql = List.of(res("/instagram/comments-response.json"));
        var ex = fetcher(web).fetch(List.of("DYtaeT4TPYu"), 5, TriggerType.MANUAL);
        assertThat(ex.items()).hasSize(5);
    }

    @Test
    void 다음페이지가_있으면_커서로_이어_수집한다() throws Exception {
        var web = new FakeWeb();
        web.html = res("/instagram/post-page.html");
        String base = res("/instagram/comments-response.json");
        // 1페이지: has_next_page=true+커서, 2페이지: 원본(false) → 15+15=30
        String page1 = base.replace("\"end_cursor\":null,\"has_next_page\":false",
                "\"end_cursor\":\"CUR\",\"has_next_page\":true");
        web.graphql = List.of(page1, base);
        var ex = fetcher(web).fetch(List.of("DYtaeT4TPYu"), 50, TriggerType.MANUAL);
        assertThat(ex.items()).hasSize(30);
    }

    @Test
    void 커서에_따옴표가_포함돼도_variables_JSON이_올바르게_이스케이프된다() throws Exception {
        var web = new FakeWeb();
        web.html = res("/instagram/post-page.html");
        String base = res("/instagram/comments-response.json");
        // 실제 IG 커서는 중첩 JSON 문자열(따옴표 포함)이다. 문자열 연결로 조립하면 깨지는 케이스를 재현한다.
        String cursorWithQuotes = "{\"is_server_cursor_inverse\":true,\"server_cursor\":\"ABC\"}";
        String escapedCursor = cursorWithQuotes.replace("\\", "\\\\").replace("\"", "\\\"");
        String page1 = base.replace("\"end_cursor\":null,\"has_next_page\":false",
                "\"end_cursor\":\"" + escapedCursor + "\",\"has_next_page\":true");
        web.graphql = List.of(page1, base);

        fetcher(web).fetch(List.of("DYtaeT4TPYu"), 50, TriggerType.MANUAL);

        String variablesParam = null;
        for (String part : web.lastBody.split("&")) {
            if (part.startsWith("variables=")) {
                variablesParam = part.substring("variables=".length());
            }
        }
        assertThat(variablesParam).isNotNull();
        String decoded = URLDecoder.decode(variablesParam, StandardCharsets.UTF_8);
        JsonNode node = om.readTree(decoded); // 파싱 실패 시 예외 발생 → 이스케이핑 회귀 방지
        assertThat(node.path("after").asString()).isEqualTo(cursorWithQuotes);
    }

    @Test
    void 여러_포스트여도_페이지GET은_한번만_한다() throws Exception {
        // 세션 재사용 최적화 회귀 방지: lsd 부트스트랩용 무거운 페이지 GET은 청크당 1회.
        var web = new FakeWeb();
        web.html = res("/instagram/post-page.html");
        web.graphql = List.of(res("/instagram/comments-response.json")); // 각 포스트 단일 페이지 15개
        var ex = fetcher(web).fetch(List.of("AAAAAAAAAAA", "BBBBBBBBBBB", "CCCCCCCCCCC"),
                50, TriggerType.MANUAL);
        assertThat(web.getCount).isEqualTo(1);   // 페이지 GET(≈600KB)은 부트스트랩 1회만
        assertThat(web.postCount).isEqualTo(3);  // graphql은 포스트당 1회
        assertThat(ex.items()).hasSize(45);      // 3 × 15
    }

    @Test
    void graphql이_차단되면_ApifyException() throws Exception {
        var web = new FakeWeb();
        web.html = res("/instagram/post-page.html");
        web.postStatus = 429;
        assertThatThrownBy(() -> fetcher(web).fetch(List.of("DYtaeT4TPYu"), 50, TriggerType.MANUAL))
                .isInstanceOf(ApifyException.class);
    }

    @Test
    void 무진행이면_중단한다() throws Exception {
        var web = new FakeWeb();
        web.html = res("/instagram/post-page.html");
        String base = res("/instagram/comments-response.json");
        // hasNext=true인데 커서가 항상 동일한 값으로 고정된 응답을 반복 수신 → 무진행 방어로 종료해야 함
        String stuck = base.replace("\"end_cursor\":null,\"has_next_page\":false",
                "\"end_cursor\":\"CUR\",\"has_next_page\":true");
        web.graphql = List.of(stuck);  // FakeWeb이 마지막 원소를 계속 반환 → 동일 응답 반복
        var ex = fetcher(web).fetch(List.of("DYtaeT4TPYu"), 50, TriggerType.MANUAL);
        assertThat(ex.items()).hasSize(30);
    }
}
