package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
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
                return new Execution(1L, r.items(), r.notFound());
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

    @Test void 프로필_요청은_여러_건이_동시에_나간다() {
        // 두 요청이 동시에 진행되어야만 latch가 풀린다 — 직렬 구현이면 첫 요청이 타임아웃돼 실패.
        // 병렬화 전제: 프록시 로테이션(요청마다 새 exit IP)이라 동시 요청도 IP가 분산된다.
        var latch = new java.util.concurrent.CountDownLatch(2);
        InstagramWebClient web = new InstagramWebClient() {
            @Override public Response get(String url) {
                latch.countDown();
                try {
                    if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new ApifyException("동시 요청 없음 — 직렬 실행");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ApifyException("인터럽트", e);
                }
                String u = url.substring(url.lastIndexOf('=') + 1);
                return new Response(200,
                        "{\"data\":{\"user\":{\"username\":\"" + u + "\",\"id\":\"1\"}}}", Map.of());
            }
            @Override public Response post(String url, String formBody, Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }
        };
        var f = new SelfProfileFetcher(web, passthroughExecutor(), new ObjectMapper(), Duration.ZERO);

        var ex = f.fetch(JobName.QUALIFY, List.of("a", "b", "c", "d"), TriggerType.MANUAL);

        assertThat(ex.items()).hasSize(4);  // 직렬이면 첫 계정이 타임아웃 스킵되어 3건
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

    @Test void 상태코드_404는_계정_소멸로_notFound에_기록한다() {
        var f = new SelfProfileFetcher(webReturning(404, "not found"), passthroughExecutor(),
                new ObjectMapper(), Duration.ZERO);

        var ex = f.fetch(JobName.QUALIFY, List.of("ghost"), TriggerType.MANUAL);

        assertThat(ex.items()).isEmpty();
        assertThat(ex.notFound()).containsExactly("ghost");
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
        // 병렬(FETCH_CONCURRENCY) 회로 차단 — 트립 시점에 진행 중이던 요청까지는 나갈 수 있다
        assertThat(calls.get()).isBetween(SelfProfileFetcher.RATE_LIMIT_STREAK_LIMIT,
                SelfProfileFetcher.RATE_LIMIT_STREAK_LIMIT + SelfProfileFetcher.FETCH_CONCURRENCY - 1);
        assertThat(calls.get()).isLessThan(20);
    }

    // 401/403(하드 블록)도 rate-limit/블록 신호 — 429처럼 연속 임계값에서 회로를 트립시킨다.
    // (인스타는 과도한 요청 시 429 소프트 → 401 하드 블록으로 에스컬레이션하므로 둘 다 잡아야 한다)
    @Test void 연속_401_하드블록도_임계값에_도달하면_배치를_중단한다() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        InstagramWebClient web = new InstagramWebClient() {
            @Override public Response get(String url) {
                calls.incrementAndGet();
                return new Response(401, "", Map.of());
            }
            @Override public Response post(String url, String formBody, Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }
        };
        var f = new SelfProfileFetcher(web, passthroughExecutor(), new ObjectMapper(), Duration.ZERO);

        List<String> many = java.util.stream.IntStream.range(0, 20).mapToObj(i -> "u" + i).toList();
        var ex = f.fetch(JobName.QUALIFY, many, TriggerType.MANUAL);

        assertThat(ex.items()).isEmpty();
        assertThat(calls.get()).isBetween(SelfProfileFetcher.RATE_LIMIT_STREAK_LIMIT,
                SelfProfileFetcher.RATE_LIMIT_STREAK_LIMIT + SelfProfileFetcher.FETCH_CONCURRENCY - 1);
    }

    // 성공(200)이 사이에 끼면 연속 카운터가 리셋 — 간헐적 429는 회로를 트립시키지 않는다.
    @Test void 성공이_사이에_끼면_429_연속_카운터가_리셋되어_중단하지_않는다() {
        // 계정별 패턴: 1회차 429 → 2회차 200. 로테이팅 프록시 전제라 재시도가 새 IP로 나가
        // 성공하고, 성공이 연속 카운터를 리셋하므로 회로는 트립하지 않는다.
        Map<String, java.util.concurrent.atomic.AtomicInteger> perUser = Map.of(
                "a", new java.util.concurrent.atomic.AtomicInteger(),
                "b", new java.util.concurrent.atomic.AtomicInteger());
        InstagramWebClient web = new InstagramWebClient() {
            @Override public Response get(String url) {
                String u = url.substring(url.lastIndexOf('=') + 1);
                if (perUser.get(u).getAndIncrement() == 0) {
                    return new Response(429, "", Map.of());
                }
                return new Response(200,
                        "{\"data\":{\"user\":{\"username\":\"" + u + "\",\"id\":\"1\"}}}", Map.of());
            }
            @Override public Response post(String url, String formBody, Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }
        };
        var f = new SelfProfileFetcher(web, passthroughExecutor(), new ObjectMapper(), Duration.ZERO);

        var ex = f.fetch(JobName.QUALIFY, List.of("a", "b"), TriggerType.MANUAL);

        assertThat(ex.items()).hasSize(2);   // 429는 재시도에서 회복 — 트립·스킵 없음
    }

    // 로테이팅 프록시는 요청마다 새 exit IP를 배정한다 — 401은 "그 IP가 차단됨"일 뿐이므로
    // 즉시 재시도(=새 IP)로 회복해야 한다. 재시도 없이 스킵하면 방문 실패율이 IP 차단율만큼 치솟는다
    // (운영 실측: APIFY 프록시 요청의 ~37%가 401 → 방문 실패 로그 홍수).
    @Test void 블록_응답은_최대_시도_횟수까지_재시도해_성공하면_확보한다() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        InstagramWebClient web = new InstagramWebClient() {
            @Override public Response get(String url) {
                if (calls.getAndIncrement() < 2) return new Response(401, "", Map.of());
                return new Response(200,
                        """
                        {"data":{"user":{"username":"retry_user","id":"1","edge_followed_by":{"count":10}}}}""",
                        Map.of());
            }
            @Override public Response post(String url, String formBody, Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }
        };
        var f = new SelfProfileFetcher(web, passthroughExecutor(), new ObjectMapper(), Duration.ZERO);

        var ex = f.fetch(JobName.COLLECT, List.of("retry_user"), TriggerType.MANUAL);

        assertThat(calls.get()).isEqualTo(3);   // 401, 401, 200
        assertThat(ex.items()).hasSize(1);
        assertThat(ProfileExtractor.username(ex.items().get(0), RawSource.SELF_GQL))
                .isEqualTo("retry_user");
    }

    @Test void 블록_응답이_최대_시도까지_계속되면_해당_계정만_스킵한다() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        InstagramWebClient web = new InstagramWebClient() {
            @Override public Response get(String url) {
                calls.incrementAndGet();
                return new Response(401, "", Map.of());
            }
            @Override public Response post(String url, String formBody, Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }
        };
        var f = new SelfProfileFetcher(web, passthroughExecutor(), new ObjectMapper(), Duration.ZERO);

        var ex = f.fetch(JobName.COLLECT, List.of("blocked_user"), TriggerType.MANUAL);

        assertThat(calls.get()).isEqualTo(SelfProfileFetcher.BLOCK_MAX_ATTEMPTS);
        assertThat(ex.items()).isEmpty();       // 소진 — 방문 실패로 다음 실행 재시도
        assertThat(ex.notFound()).isEmpty();    // 404(계정 소멸)와는 구분된다
    }

    @Test void 상태코드_400은_badRequestOut에_수집되고_스킵된다() {
        var f = new SelfProfileFetcher(webReturning(400, "{\"status\":\"fail\"}"), passthroughExecutor(),
                new ObjectMapper(), Duration.ZERO);

        List<String> badRequest = new java.util.ArrayList<>();
        ApifyResult r = f.collect(List.of("bugged"), badRequest);

        assertThat(r.items()).isEmpty();
        assertThat(r.notFound()).isEmpty();   // 404(계정 소멸)와 구분된다
        assertThat(badRequest).containsExactly("bugged");
    }

    // 400은 블록(429·401·403) 신호가 아니다 — 연속으로 나와도 회로를 트립시키지 않고 전 계정을 조회한다.
    @Test void 연속_400은_회로를_트립시키지_않는다() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        InstagramWebClient web = new InstagramWebClient() {
            @Override public Response get(String url) {
                calls.incrementAndGet();
                return new Response(400, "{\"status\":\"fail\"}", Map.of());
            }
            @Override public Response post(String url, String formBody, Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }
        };
        var f = new SelfProfileFetcher(web, passthroughExecutor(), new ObjectMapper(), Duration.ZERO);

        List<String> badRequest = new java.util.ArrayList<>();
        List<String> many = java.util.stream.IntStream.range(0, 20).mapToObj(i -> "u" + i).toList();
        f.collect(many, badRequest);

        assertThat(calls.get()).isEqualTo(20);            // 중단 없음 — 계정당 1회씩 전부 조회
        assertThat(badRequest).hasSize(20);
    }
}
