package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.celfit.crawler.crawling.domain.JobName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SelfProfileFetcherTest {

    // CrawlExecutor의 Supplier 오버로드만 흉내내는 최소 스텁 (spy 대신 서브클래스)
    static CrawlExecutor passthroughExecutor() {
        return new CrawlExecutor(null, null, null, null) {
            @Override public Execution execute(JobName job,
                                               TriggerType t, String keyword, String targetUsername, String actorId,
                                               Supplier<ApifyResult> work) {
                ApifyResult r = work.get();
                return new Execution(1L, r.items());
            }
        };
    }

    private static InstagramWebClient webReturning(int status, String body) {
        return new InstagramWebClient() {
            @Override public Response get(String url) {
                return new Response(status, body, Map.of());
            }

            @Override public Response post(String url, String formBody, Map<String, String> headers) {
                throw new UnsupportedOperationException("web_profile_info는 GET만 사용");
            }
        };
    }

    @Test void source는_SELF_rawSource는_SELF_GQL() {
        var f = new SelfProfileFetcher(webReturning(200, null), passthroughExecutor(),
                new ObjectMapper(), Duration.ZERO);
        assertThat(f.source()).isEqualTo(ProfileSource.SELF);
        assertThat(f.rawSource()).isEqualTo(RawSource.SELF_GQL);
    }

    @Test void 각_username마다_web_profile_info_호출후_응답_원형을_그대로_반환() {
        InstagramWebClient web = webReturning(200,
            """
            {"data":{"user":{"username":"beauty.e.ze","id":"74851841915","edge_followed_by":{"count":2369}}}}""");
        var f = new SelfProfileFetcher(web, passthroughExecutor(),
                new ObjectMapper(), Duration.ZERO);

        var ex = f.fetch(JobName.QUALIFY, List.of("beauty.e.ze"), TriggerType.MANUAL);
        assertThat(ex.items()).hasSize(1);
        Map<String, Object> item = ex.items().get(0);
        assertThat(ProfileExtractor.username(item, RawSource.SELF_GQL)).isEqualTo("beauty.e.ze");
        assertThat(ProfileExtractor.followers(item, RawSource.SELF_GQL)).isEqualTo(2369L);
        assertThat(ProfileExtractor.userId(item, RawSource.SELF_GQL)).isEqualTo("74851841915");
        assertThat(item).containsKey("data"); // 원형 그대로 보존 — 정규화된 평탄 필드가 아님
    }

    @Test void 상태코드가_200이_아니면_스킵() {
        InstagramWebClient web = webReturning(404, "not found");
        var f = new SelfProfileFetcher(web, passthroughExecutor(),
                new ObjectMapper(), Duration.ZERO);

        var ex = f.fetch(JobName.QUALIFY, List.of("ghost"), TriggerType.MANUAL);
        assertThat(ex.items()).isEmpty();
    }

    @Test void 요청_예외는_해당_계정만_건너뛰고_나머지는_계속한다() {
        // 프록시가 커넥션을 중간에 끊으면(TLS BUFFER_UNDERFLOW 등) web.get이 ApifyException을
        // 던진다 — 계정 1명 때문에 청크(최대 50명) 전체가 FAILED로 무너지면 안 된다.
        InstagramWebClient web = new InstagramWebClient() {
            @Override public Response get(String url) {
                if (url.endsWith("flaky_user")) {
                    throw new com.celfit.crawler.crawling.application.port.out.ApifyException(
                            "인스타 요청 실패: BUFFER_UNDERFLOW with EOF");
                }
                return new Response(200,
                        """
                        {"data":{"user":{"username":"ok_user","id":"1","edge_followed_by":{"count":10}}}}""",
                        Map.of());
            }

            @Override public Response post(String url, String formBody, Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }
        };
        var f = new SelfProfileFetcher(web, passthroughExecutor(), new ObjectMapper(), Duration.ZERO);

        var ex = f.fetch(JobName.QUALIFY, List.of("flaky_user", "ok_user"), TriggerType.MANUAL);

        assertThat(ex.items()).hasSize(1);
        assertThat(ProfileExtractor.username(ex.items().get(0), RawSource.SELF_GQL)).isEqualTo("ok_user");
    }

    // 429(rate limit)가 연속으로 임계값만큼 나면 IP 스로틀 신호로 보고 남은 계정을 두드리지 않고 중단한다.
    // (스로틀 상태에서 계속 두드리면 스로틀만 깊어지고 홈 IP까지 위험)
    @Test void 연속_429가_임계값에_도달하면_남은_계정을_두드리지_않고_중단한다() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        InstagramWebClient web = new InstagramWebClient() {
            @Override public Response get(String url) {
                calls.incrementAndGet();
                return new Response(429, "", Map.of());
            }
            @Override public Response post(String url, String formBody, Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }
        };
        var f = new SelfProfileFetcher(web, passthroughExecutor(), new ObjectMapper(), Duration.ZERO);

        // 20명을 넣어도 임계값만큼만 호출하고 멈춘다.
        List<String> many = java.util.stream.IntStream.range(0, 20).mapToObj(i -> "u" + i).toList();
        var ex = f.fetch(JobName.QUALIFY, many, TriggerType.MANUAL);

        assertThat(ex.items()).isEmpty();
        assertThat(calls.get()).isEqualTo(SelfProfileFetcher.RATE_LIMIT_STREAK_LIMIT);
        assertThat(calls.get()).isLessThan(20);
    }

    // 성공(200)이 사이에 끼면 연속 카운터가 리셋 — 간헐적 429는 회로를 트립시키지 않는다.
    @Test void 성공이_사이에_끼면_429_연속_카운터가_리셋되어_중단하지_않는다() {
        // 패턴: 429, 429, 200, 429, 429 ... (연속 429가 임계값에 도달하지 않음)
        java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger();
        InstagramWebClient web = new InstagramWebClient() {
            @Override public Response get(String url) {
                int n = idx.getAndIncrement();
                if (n == 2) {
                    return new Response(200,
                            """
                            {"data":{"user":{"username":"ok_user","id":"1","edge_followed_by":{"count":10}}}}""",
                            Map.of());
                }
                return new Response(429, "", Map.of());
            }
            @Override public Response post(String url, String formBody, Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }
        };
        var f = new SelfProfileFetcher(web, passthroughExecutor(), new ObjectMapper(), Duration.ZERO);

        List<String> five = List.of("a", "b", "ok_user", "d", "e"); // 총 5명
        var ex = f.fetch(JobName.QUALIFY, five, TriggerType.MANUAL);

        // 200이 3번째에서 카운터를 리셋했으므로 이후 429 2연속으론 임계값(5) 미달 → 5명 전부 시도
        assertThat(idx.get()).isEqualTo(5);
        assertThat(ex.items()).hasSize(1);
    }
}
