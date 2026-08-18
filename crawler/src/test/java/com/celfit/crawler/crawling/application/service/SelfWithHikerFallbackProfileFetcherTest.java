package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import com.celfit.crawler.crawling.application.port.out.PaidCallCounter;
import com.celfit.crawler.crawling.application.port.out.NotFoundException;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SelfWithHikerFallbackProfileFetcherTest {

    static CrawlExecutor passthrough() { return SelfProfileFetcherTest.passthroughExecutor(); }
    ObjectMapper om = new ObjectMapper();

    /** bugged 집합은 400, 나머지는 SELF_GQL 원형 200. */
    static InstagramWebClient webWith400For(Set<String> bugged) {
        return new InstagramWebClient() {
            @Override public Response get(String url) {
                String u = url.substring(url.lastIndexOf('=') + 1);
                if (bugged.contains(u)) return new Response(400, "{\"status\":\"fail\"}", Map.of());
                return new Response(200,
                        "{\"data\":{\"user\":{\"username\":\"" + u + "\",\"id\":\"1\"}}}", Map.of());
            }
            @Override public Response post(String url, String formBody, Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }
        };
    }

    SelfWithHikerFallbackProfileFetcher fetcher(InstagramWebClient web, HikerHttp http) {
        var self = new SelfProfileFetcher(web, passthrough(), om, Duration.ZERO);
        var hiker = new HikerMobileProfileFetcher(http, passthrough(), new PaidCallCounter(), om);
        return new SelfWithHikerFallbackProfileFetcher(self, hiker, passthrough());
    }

    @Test void 소스는_SELF_HIKER_FALLBACK_기본_rawSource는_SELF_GQL() {
        var f = fetcher(webWith400For(Set.of()), path -> { throw new AssertionError("호출되면 안됨"); });
        assertThat(f.source()).isEqualTo(ProfileSource.SELF_HIKER_FALLBACK);
        assertThat(f.rawSource()).isEqualTo(RawSource.SELF_GQL);
    }

    @Test void 자체조회_400_계정만_Hiker로_폴백되어_병합된다() {
        AtomicInteger hikerCalls = new AtomicInteger();
        HikerHttp http = path -> {
            hikerCalls.incrementAndGet();
            assertThat(path).contains("/v2/user/by/username").contains("username=bugged");
            return "{\"user\":{\"username\":\"bugged\",\"pk\":\"2\"}}";
        };
        var f = fetcher(webWith400For(Set.of("bugged")), http);

        var ex = f.fetch(JobName.QUALIFY, List.of("ok", "bugged"), TriggerType.MANUAL);

        assertThat(hikerCalls.get()).isEqualTo(1);   // 정상 계정은 Hiker 미호출
        assertThat(ex.items()).hasSize(2);
        // 아이템별 원형이 섞여 있고, detect로 구분된다
        var sources = ex.items().stream().map(i -> ProfileExtractor.detect(i, RawSource.SELF_GQL)).toList();
        assertThat(sources).containsExactlyInAnyOrder(RawSource.SELF_GQL, RawSource.HIKER_MOBILE);
    }

    @Test void 자체조회_400이_없으면_Hiker를_호출하지_않는다() {
        var f = fetcher(webWith400For(Set.of()), path -> { throw new AssertionError("호출되면 안됨"); });

        var ex = f.fetch(JobName.QUALIFY, List.of("a", "b"), TriggerType.MANUAL);

        assertThat(ex.items()).hasSize(2);
    }

    @Test void 폴백_조회의_404는_notFound로_병합된다() {
        // SELF에서 400이 났지만 Hiker 기준으로는 계정 소멸 — 소프트 딜리트 경로로 종결돼야 한다
        HikerHttp http = path -> { throw new NotFoundException("Hiker HTTP 404"); };
        var f = fetcher(webWith400For(Set.of("gone")), http);

        var ex = f.fetch(JobName.QUALIFY, List.of("ok", "gone"), TriggerType.MANUAL);

        assertThat(ex.items()).hasSize(1);
        assertThat(ex.notFound()).containsExactly("gone");
    }

    // ── 빈 응답(200 + user 없음) 폴백 — IG가 일부 계정에 익명 API에서 계정을 숨기는 케이스 ──

    static final int THRESHOLD = SelfWithHikerFallbackProfileFetcher.EMPTY_STREAK_FALLBACK_THRESHOLD;

    /** empty 집합은 200 + user null(빈 응답), 나머지는 SELF_GQL 원형 200. */
    static InstagramWebClient webWithEmptyFor(Set<String> empty) {
        return new InstagramWebClient() {
            @Override public Response get(String url) {
                String u = url.substring(url.lastIndexOf('=') + 1);
                if (empty.contains(u)) {
                    return new Response(200, "{\"data\":{\"user\":null},\"status\":\"ok\"}", Map.of());
                }
                return new Response(200,
                        "{\"data\":{\"user\":{\"username\":\"" + u + "\",\"id\":\"1\"}}}", Map.of());
            }
            @Override public Response post(String url, String formBody, Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test void 빈_응답은_임계값_미만이면_폴백하지_않고_기존_재시도_경로로_남긴다() {
        // 진짜 소멸 계정(비활성화·탈퇴 유예)에 첫 빈 응답부터 유료 콜을 쓰지 않는다
        AtomicInteger hikerCalls = new AtomicInteger();
        HikerHttp http = path -> {
            hikerCalls.incrementAndGet();
            return "{\"user\":{\"username\":\"hidden\",\"pk\":\"2\"}}";
        };
        var f = fetcher(webWithEmptyFor(Set.of("hidden")), http);

        var ex = f.fetch(JobName.COLLECT, List.of("hidden"), TriggerType.MANUAL);

        assertThat(hikerCalls.get()).isZero();
        assertThat(ex.items()).isEmpty();     // 방문 실패 → 다음 실행 재시도
        assertThat(ex.notFound()).isEmpty();
    }

    @Test void 빈_응답_폴백은_COLLECT_잡에서만_작동한다() {
        // qualify는 대상 재선정에 종결 장치(30일 정책)가 없다 — 빈 응답 유료 폴백을 열면
        // 숨겨진 DISCOVERED 계정이 무한 재과금된다. 400 폴백은 두 잡 모두 기존대로.
        AtomicInteger hikerCalls = new AtomicInteger();
        HikerHttp http = path -> {
            hikerCalls.incrementAndGet();
            return "{\"user\":{\"username\":\"hidden\",\"pk\":\"2\"}}";
        };
        var f = fetcher(webWithEmptyFor(Set.of("hidden")), http);

        CrawlExecutor.Execution ex = null;
        for (int round = 1; round <= THRESHOLD + 1; round++) {
            ex = f.fetch(JobName.QUALIFY, List.of("hidden"), TriggerType.MANUAL);
        }

        assertThat(hikerCalls.get()).isZero();   // 임계값을 넘겨도 qualify에선 폴백 없음
        assertThat(ex.items()).isEmpty();
        assertThat(ex.confirmedEmpty()).isEmpty();
    }

    @Test void 폴백_요청_실패는_확인된_빈_계정으로_보고되지_않고_다음_방문에_폴백을_재시도한다() {
        // Hiker 5xx·타임아웃은 "계정 없음 확인"이 아니다 — 인프라 오류로 소프트 삭제가
        // 트리거되면 안 되고, 카운터를 유지해 다음 방문에서 폴백을 다시 시도한다
        AtomicInteger hikerCalls = new AtomicInteger();
        HikerHttp http = path -> {
            hikerCalls.incrementAndGet();
            throw new com.celfit.crawler.crawling.application.port.out.ApifyException("Hiker HTTP 503");
        };
        var f = fetcher(webWithEmptyFor(Set.of("unlucky")), http);

        CrawlExecutor.Execution ex = null;
        for (int round = 1; round <= THRESHOLD + 1; round++) {
            ex = f.fetch(JobName.COLLECT, List.of("unlucky"), TriggerType.MANUAL);
        }

        assertThat(hikerCalls.get()).isEqualTo(2);       // 임계값 회차 + 재시도 회차
        assertThat(ex.confirmedEmpty()).isEmpty();       // 요청 실패 ≠ 빈 응답 확인
        assertThat(ex.items()).isEmpty();
        assertThat(ex.notFound()).isEmpty();
    }

    @Test void 빈_응답이_연속_임계값에_도달하면_Hiker로_폴백되어_병합된다() {
        AtomicInteger hikerCalls = new AtomicInteger();
        HikerHttp http = path -> {
            hikerCalls.incrementAndGet();
            assertThat(path).contains("/v2/user/by/username").contains("username=hidden");
            return "{\"user\":{\"username\":\"hidden\",\"pk\":\"2\"}}";
        };
        var f = fetcher(webWithEmptyFor(Set.of("hidden")), http);

        CrawlExecutor.Execution ex = null;
        for (int round = 1; round <= THRESHOLD; round++) {
            ex = f.fetch(JobName.COLLECT, List.of("hidden"), TriggerType.MANUAL);
        }

        assertThat(hikerCalls.get()).isEqualTo(1);   // 임계값 도달 회차에만 호출
        assertThat(ex.items()).hasSize(1);
        assertThat(ProfileExtractor.detect(ex.items().get(0), RawSource.SELF_GQL))
                .isEqualTo(RawSource.HIKER_MOBILE);
    }

    @Test void SELF_성공이_끼면_빈_응답_연속_카운터가_리셋된다() {
        AtomicInteger hikerCalls = new AtomicInteger();
        HikerHttp http = path -> { hikerCalls.incrementAndGet(); return "{\"user\":null}"; };
        Set<String> empty = new java.util.HashSet<>(Set.of("wobbly"));
        InstagramWebClient web = new InstagramWebClient() {
            @Override public Response get(String url) {
                return webWithEmptyFor(empty).get(url);
            }
            @Override public Response post(String url, String formBody, Map<String, String> headers) {
                throw new UnsupportedOperationException();
            }
        };
        var f = fetcher(web, http);

        // 임계값 직전까지 빈 응답 → SELF 성공 1회(리셋) → 다시 빈 응답 1회: 연속이 아니므로 폴백 없음
        for (int round = 1; round < THRESHOLD; round++) {
            f.fetch(JobName.COLLECT, List.of("wobbly"), TriggerType.MANUAL);
        }
        empty.clear();
        f.fetch(JobName.COLLECT, List.of("wobbly"), TriggerType.MANUAL);
        empty.add("wobbly");
        var ex = f.fetch(JobName.COLLECT, List.of("wobbly"), TriggerType.MANUAL);

        assertThat(hikerCalls.get()).isZero();
        assertThat(ex.items()).isEmpty();
    }

    @Test void 폴백도_빈_응답이면_확인된_빈_계정으로_보고된다() {
        // 호출자(CollectJob)가 30일 수명 정책을 판정할 수 있는 유일한 신호 — 양쪽 소스 모두
        // 계정이 없음을 이번 방문에서 확인했다는 뜻이다
        HikerHttp http = path -> "{\"user\":null}";
        var f = fetcher(webWithEmptyFor(Set.of("dormant")), http);

        CrawlExecutor.Execution ex = null;
        for (int round = 1; round <= THRESHOLD; round++) {
            ex = f.fetch(JobName.COLLECT, List.of("dormant"), TriggerType.MANUAL);
        }

        assertThat(ex.confirmedEmpty()).containsExactly("dormant");
        assertThat(ex.items()).isEmpty();
        assertThat(ex.notFound()).isEmpty();
    }

    @Test void 임계값_미만의_빈_응답은_확인된_빈_계정으로_보고되지_않는다() {
        // Hiker 미확인 상태 — 소멸 판정 재료로 쓰면 안 된다
        var f = fetcher(webWithEmptyFor(Set.of("hidden")), path -> "{\"user\":null}");

        var ex = f.fetch(JobName.COLLECT, List.of("hidden"), TriggerType.MANUAL);

        assertThat(ex.confirmedEmpty()).isEmpty();
    }

    @Test void 폴백도_빈_응답이면_카운터를_리셋하고_기존_재시도_경로로_복귀한다() {
        // 진짜 소멸 계정 — 유료 콜이 임계값 주기당 1회로 묶여야 한다(매일 반복 과금 방지)
        AtomicInteger hikerCalls = new AtomicInteger();
        HikerHttp http = path -> { hikerCalls.incrementAndGet(); return "{\"user\":null}"; };
        var f = fetcher(webWithEmptyFor(Set.of("dormant")), http);

        CrawlExecutor.Execution ex = null;
        // 임계값 도달 → 폴백(빈 응답) → 리셋 — 직후 (임계값-1)회는 다시 폴백하지 않는다
        for (int round = 1; round <= THRESHOLD + THRESHOLD - 1; round++) {
            ex = f.fetch(JobName.COLLECT, List.of("dormant"), TriggerType.MANUAL);
        }

        assertThat(hikerCalls.get()).isEqualTo(1);
        assertThat(ex.items()).isEmpty();     // 방문 실패 유지 — 성공 처리하지 않는다
        assertThat(ex.notFound()).isEmpty();
    }

    @Test void 폴백_성공_후에는_다음_빈_응답에서_즉시_폴백한다() {
        // Hiker로 수집 가능함이 확인된 계정 — 임계값을 다시 기다리며 방문 실패를 반복하지 않는다
        AtomicInteger hikerCalls = new AtomicInteger();
        HikerHttp http = path -> {
            hikerCalls.incrementAndGet();
            return "{\"user\":{\"username\":\"hidden\",\"pk\":\"2\"}}";
        };
        var f = fetcher(webWithEmptyFor(Set.of("hidden")), http);

        CrawlExecutor.Execution ex = null;
        for (int round = 1; round <= THRESHOLD + 1; round++) {
            ex = f.fetch(JobName.COLLECT, List.of("hidden"), TriggerType.MANUAL);
        }

        assertThat(hikerCalls.get()).isEqualTo(2);   // 임계값 회차 + 그 다음 회차
        assertThat(ex.items()).hasSize(1);
    }

    @Test void 빈_응답_폴백의_404는_notFound로_병합된다() {
        // SELF는 빈 응답을 줬지만 Hiker 기준 계정 소멸 — 소프트 딜리트 경로로 종결
        HikerHttp http = path -> { throw new NotFoundException("Hiker HTTP 404"); };
        var f = fetcher(webWithEmptyFor(Set.of("gone")), http);

        CrawlExecutor.Execution ex = null;
        for (int round = 1; round <= THRESHOLD; round++) {
            ex = f.fetch(JobName.COLLECT, List.of("gone"), TriggerType.MANUAL);
        }

        assertThat(ex.items()).isEmpty();
        assertThat(ex.notFound()).containsExactly("gone");
    }
}
