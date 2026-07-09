package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.CommentSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class DirectCommentFetcherTest extends IntegrationTest {

    @Autowired CrawlExecutor executor;
    @Autowired CommentMapper mapper;   // tools.jackson ObjectMapper가 주입된 빈

    static String res(String p) throws Exception {
        return new String(DirectCommentFetcherTest.class.getResourceAsStream(p).readAllBytes());
    }

    // Fake 웹클라이언트: 페이지 HTML과 graphql 응답들을 순서대로 반환
    static class FakeWeb implements InstagramWebClient {
        String html; List<String> graphql; int i = 0; int getStatus = 200; int postStatus = 200;
        public Response get(String url) { return new Response(getStatus, html, Map.of()); }
        public Response post(String url, String body, Map<String, String> h) {
            if (postStatus >= 300) return new Response(postStatus, "blocked", Map.of());
            return new Response(200, graphql.get(Math.min(i++, graphql.size() - 1)), Map.of());
        }
    }

    DirectCommentFetcher fetcher(FakeWeb web) {
        return new DirectCommentFetcher(web, executor, mapper, Duration.ZERO, "DOC", "FRIENDLY");
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
