package com.celfit.crawler.apify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.crawler.config.ApifyProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ApifyClientTest {

    /** 스크립트된 응답을 순서대로 돌려주고 호출 url을 기록하는 fake. */
    static class FakeHttp implements ApifyHttp {
        final Deque<String> responses = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();

        @Override public String get(String url) {
            calls.add("GET " + url);
            return responses.pop();
        }

        @Override public String post(String url, String body) {
            calls.add("POST " + url);
            return responses.pop();
        }
    }

    /** sleep할 때마다 가짜 시계를 전진시킨다. */
    static class TickingClock extends Clock {
        Instant now = Instant.parse("2026-07-07T00:00:00Z");
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    static final String STARTED = """
            {"data": {"id": "run-1", "defaultDatasetId": "ds-1", "status": "READY"}}""";

    ApifyClient client(FakeHttp http, TickingClock clock, Duration timeout) {
        ApifyProperties props = new ApifyProperties(
                "tok", "https://api.test", Duration.ofSeconds(5), timeout);
        Sleeper advancing = d -> clock.now = clock.now.plus(d);
        return new ApifyClient(http, props, new ObjectMapper(), advancing, clock);
    }

    @Test
    void 폴링_후_SUCCEEDED면_dataset_아이템을_반환한다() {
        FakeHttp http = new FakeHttp();
        http.responses.add(STARTED);
        http.responses.add("""
                {"data": {"status": "RUNNING"}}""");
        http.responses.add("""
                {"data": {"status": "SUCCEEDED"}}""");
        http.responses.add("""
                [{"shortCode": "abc"}, {"shortCode": "def"}]""");

        ApifyResult result = client(http, new TickingClock(), Duration.ofMinutes(10))
                .run("apify~instagram-hashtag-scraper", java.util.Map.of("k", "v"));

        assertThat(result.runId()).isEqualTo("run-1");
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0)).containsEntry("shortCode", "abc");
        assertThat(http.calls.get(0)).startsWith("POST https://api.test/v2/acts/apify~instagram-hashtag-scraper/runs");
        assertThat(http.calls.get(3)).contains("/v2/datasets/ds-1/items");
    }

    @Test
    void run이_FAILED면_예외() {
        FakeHttp http = new FakeHttp();
        http.responses.add(STARTED);
        http.responses.add("""
                {"data": {"status": "FAILED"}}""");

        assertThatThrownBy(() -> client(http, new TickingClock(), Duration.ofMinutes(10))
                .run("actor", java.util.Map.of()))
                .isInstanceOf(ApifyException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void 타임아웃되면_abort_호출_후_예외() {
        FakeHttp http = new FakeHttp();
        http.responses.add(STARTED);
        http.responses.add("""
                {"data": {"status": "RUNNING"}}""");
        http.responses.add("""
                {"data": {"status": "RUNNING"}}""");
        http.responses.add("""
                {"data": {"status": "ABORTING"}}""");  // abort 응답
        // 타임라인: t=0 poll(RUNNING)→sleep→t=5 poll(RUNNING)→deadline(4s) 초과→abort
        assertThatThrownBy(() -> client(http, new TickingClock(), Duration.ofSeconds(4))
                .run("actor", java.util.Map.of()))
                .isInstanceOf(ApifyException.class)
                .hasMessageContaining("타임아웃");
        assertThat(http.calls).anyMatch(c -> c.startsWith("POST") && c.contains("/abort"));
    }
}
