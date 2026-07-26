> 상태: ✅ 실행됨 (2026-07-26 — 태스크 1~6 완료, 아카이브)

# 프로필 400 → Hiker 폴백(`SELF_HIKER_FALLBACK`) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `web_profile_info`(SELF)가 HTTP 400을 반환하는 계정만 HikerAPI로 폴백하는 새 프로필 소스 `SELF_HIKER_FALLBACK`을 추가한다.

**Architecture:** 컴포지트 페처가 SELF 배치 조회 중 400 계정을 수집해 `HikerMobileProfileFetcher` 경로로 2차 조회하고 결과를 병합한다(crawl_run 1건 유지). 혼합 배치의 아이템별 소스는 `ProfileExtractor.detect` 셰이프 감지로 구분하며, 소비처 3곳(CollectJob·QualifyJob·ProfileSupplementer)이 이를 사용한다. 스펙: [specs/2026-07-26-profile-400-hiker-fallback-design.md](../specs/2026-07-26-profile-400-hiker-fallback-design.md)

**Tech Stack:** Java 21, Spring Boot 4.1, Jackson 3(`tools.jackson.*`), JUnit 5 + AssertJ + Mockito. 모듈: `crawler`.

## Global Constraints

- 주석·로그·커밋 메시지는 한국어. 커밋 prefix `feat(crawler):` / `docs:`.
- 테스트: `./gradlew :crawler:test` (전체는 `./gradlew test`).
- 응답 payload는 "원형 그대로" 저장 — 페이로드에 마커 필드 주입 금지.
- 실제 소스 전환(`app_setting`의 `profile.source` UPDATE)은 **이 작업 범위 밖** — 옵션 추가까지만.
- 스펙 문서(`docs/superpowers/specs/…`)는 내용 불변 — 상태 헤더만 갱신 가능.

**스펙 대비 구현 디테일 1건:** `detect`는 `defaultSource != SELF_GQL`이면 즉시 defaultSource를 반환한다(감지 스킵). DATALIKERS 원형이 flat(`pk` 최상위)이라 무조건 감지하면 HIKER_MOBILE로 오기록된다 — 스펙의 "기존 소스 보호" 의도를 게이트로 강화한 것. 감지가 실제로 필요한 경우는 SELF 계열 배치(SELF·SELF_HIKER_FALLBACK, 기본 소스 SELF_GQL)뿐이다.

---

### Task 1: `ProfileExtractor.detect` — 아이템별 소스 감지

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/ProfileExtractor.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/ProfileExtractorTest.java` (기존 클래스에 테스트 추가)

**Interfaces:**
- Produces: `public static RawSource detect(Map<String, Object> payload, RawSource defaultSource)` — Task 3(컴포지트 검증)·Task 4(소비처)가 사용.

- [ ] **Step 1: 실패하는 테스트 작성** — `ProfileExtractorTest`에 추가 (기존 파일의 import·스타일에 맞춰 배치):

```java
@Test void detect는_data_루트를_SELF_GQL로_감지한다() {
    Map<String, Object> payload = Map.of("data", Map.of("user", Map.of("username", "a")));
    assertThat(ProfileExtractor.detect(payload, RawSource.SELF_GQL)).isEqualTo(RawSource.SELF_GQL);
}

@Test void detect는_user_래퍼_또는_pk_최상위를_HIKER_MOBILE로_감지한다() {
    Map<String, Object> wrapped = Map.of("user", Map.of("username", "a", "pk", "1"));
    Map<String, Object> flat = Map.of("username", "a", "pk", "1");
    assertThat(ProfileExtractor.detect(wrapped, RawSource.SELF_GQL)).isEqualTo(RawSource.HIKER_MOBILE);
    assertThat(ProfileExtractor.detect(flat, RawSource.SELF_GQL)).isEqualTo(RawSource.HIKER_MOBILE);
}

@Test void detect는_SELF_GQL_배치가_아니면_감지_없이_기본_소스를_반환한다() {
    // DATALIKERS 원형은 flat(pk 최상위) — 감지하면 HIKER_MOBILE로 오기록되므로 게이트로 보호
    Map<String, Object> flat = Map.of("username", "a", "pk", "1");
    assertThat(ProfileExtractor.detect(flat, RawSource.DATALIKERS)).isEqualTo(RawSource.DATALIKERS);
    assertThat(ProfileExtractor.detect(flat, RawSource.APIFY_ACTOR)).isEqualTo(RawSource.APIFY_ACTOR);
}

@Test void detect는_모르는_형태면_기본_소스를_반환한다() {
    Map<String, Object> unknown = Map.of("username", "a", "followersCount", 1L);
    assertThat(ProfileExtractor.detect(unknown, RawSource.SELF_GQL)).isEqualTo(RawSource.SELF_GQL);
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :crawler:test --tests 'com.celfit.crawler.crawling.application.service.ProfileExtractorTest'`
Expected: FAIL — `detect` 심볼 없음(컴파일 에러).

- [ ] **Step 3: 구현** — `ProfileExtractor`에 public static 메서드 추가 (`followers` 위쪽에 배치):

```java
/**
 * 혼합 배치(SELF 베이스 + 400 Hiker 폴백)의 아이템별 소스 감지. SELF_GQL 원형은 루트에
 * "data", HIKER_MOBILE 원형은 "user" 래퍼 또는 flat "pk"가 있다. SELF_GQL 기본이 아닌
 * 배치는 감지하지 않는다 — DATALIKERS 등 flat 원형이 HIKER_MOBILE로 오기록되는 것을 막는다.
 */
public static RawSource detect(Map<String, Object> payload, RawSource defaultSource) {
    if (defaultSource != RawSource.SELF_GQL) return defaultSource;
    if (payload.get("data") instanceof Map) return RawSource.SELF_GQL;
    if (payload.get("user") instanceof Map || payload.containsKey("pk")) return RawSource.HIKER_MOBILE;
    return defaultSource;
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :crawler:test --tests 'com.celfit.crawler.crawling.application.service.ProfileExtractorTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/crawling/application/service/ProfileExtractor.java crawler/src/test/java/com/celfit/crawler/crawling/application/service/ProfileExtractorTest.java
git commit -m "feat(crawler): ProfileExtractor.detect — 혼합 배치 아이템별 소스 감지"
```

---

### Task 2: `SelfProfileFetcher` — HTTP 400 계정 수집

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/SelfProfileFetcher.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/SelfProfileFetcherTest.java`

**Interfaces:**
- Produces: 패키지 가시성 `ApifyResult collect(List<String> usernames, List<String> badRequestOut)` — Task 3의 컴포지트가 호출. badRequestOut에는 HTTP 400이 난 username이 담긴다(스레드 세이프 래핑은 메서드 내부에서 함 — 호출자는 일반 ArrayList를 넘기면 된다). 공개 `fetch()` 경로의 400 동작(스킵, items·notFound 미포함)은 불변.

- [ ] **Step 1: 실패하는 테스트 작성** — `SelfProfileFetcherTest`에 추가:

```java
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
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :crawler:test --tests 'com.celfit.crawler.crawling.application.service.SelfProfileFetcherTest'`
Expected: FAIL — 2-인자 `collect` 없음(컴파일 에러).

- [ ] **Step 3: 구현** — `SelfProfileFetcher.java` 수정 3곳:

(1) 기존 `private ApifyResult collect(List<String> usernames)`를 위임으로 바꾸고 2-인자 버전 추가 — 본문은 기존 그대로 유지하되 `badRequest`를 만들어 `fetchOne`에 전달:

```java
    private ApifyResult collect(List<String> usernames) {
        return collect(usernames, new ArrayList<>());
    }

    /**
     * 컴포지트(SELF_HIKER_FALLBACK)용 — HTTP 400이 난 계정을 badRequestOut에 수집한다.
     * 400 수집 외 동작은 단독 SELF와 동일(400 계정은 items·notFound에 안 들어가고 스킵).
     */
    ApifyResult collect(List<String> usernames, List<String> badRequestOut) {
        List<Map<String, Object>> out = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> notFound = java.util.Collections.synchronizedList(new ArrayList<>());
        // 워커들이 동시에 add하므로 동기화 래핑 — 호출자는 일반 리스트를 넘겨도 된다
        List<String> badRequest = java.util.Collections.synchronizedList(badRequestOut);
        int total = usernames.size();
        var done = new java.util.concurrent.atomic.AtomicInteger();
        var rateLimitStreak = new java.util.concurrent.atomic.AtomicInteger();
        var tripped = new java.util.concurrent.atomic.AtomicBoolean(false);
        // 1명(collect 방문 경로)은 풀 없이 즉시 처리 — 방문마다 스레드풀을 만들 이유가 없다
        if (total == 1) {
            fetchOne(usernames.get(0), total, done, rateLimitStreak, tripped, out, notFound, badRequest);
            return new ApifyResult(null, out, notFound);
        }
        // close()가 제출된 작업 완료까지 대기(Java 21) — 반환 시점에 결과가 전부 모여 있다
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(FETCH_CONCURRENCY)) {
            for (int w = 0; w < FETCH_CONCURRENCY; w++) {
                final int offset = w;
                pool.submit(() -> {
                    for (int idx = offset; idx < total; idx += FETCH_CONCURRENCY) {
                        if (tripped.get()) return;   // 회로 트립 — 남은 계정은 다음 실행 재시도
                        fetchOne(usernames.get(idx), total, done, rateLimitStreak, tripped, out, notFound, badRequest);
                        sleep();
                    }
                });
            }
        }
        return new ApifyResult(null, out, notFound);
    }
```

(2) `fetchOne` 시그니처에 `List<String> badRequest` 파라미터 추가(마지막 인자):

```java
    private void fetchOne(String u, int total, java.util.concurrent.atomic.AtomicInteger done,
                          java.util.concurrent.atomic.AtomicInteger rateLimitStreak,
                          java.util.concurrent.atomic.AtomicBoolean tripped,
                          List<Map<String, Object>> out, List<String> notFound,
                          List<String> badRequest) {
```

(3) `fetchOne` 본문의 `if (res.status() == 404)` 분기 **앞에** 400 분기 추가:

```java
                if (res.status() == 400) {
                    // IP 무관 400(비즈니스 카테고리 버그) — 재시도 무의미. 컴포지트가 Hiker로
                    // 폴백할 수 있게 수집만 하고 스킵한다(블록 신호가 아니므로 회로 카운터 리셋).
                    rateLimitStreak.set(0);
                    badRequest.add(u);
                    log.info("프로필 ({}/{}) {} — HTTP 400(버그 계정) 스킵, 폴백 대상 수집", i, total, u);
                    return;
                }
```

- [ ] **Step 4: 통과 확인 (기존 테스트 포함 전체)**

Run: `./gradlew :crawler:test --tests 'com.celfit.crawler.crawling.application.service.SelfProfileFetcherTest'`
Expected: PASS (기존 회로 차단·재시도 테스트 포함 전부)

- [ ] **Step 5: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/crawling/application/service/SelfProfileFetcher.java crawler/src/test/java/com/celfit/crawler/crawling/application/service/SelfProfileFetcherTest.java
git commit -m "feat(crawler): SelfProfileFetcher가 HTTP 400 계정을 폴백 대상으로 수집"
```

---

### Task 3: 컴포지트 페처 + `ProfileSource.SELF_HIKER_FALLBACK`

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/settings/domain/ProfileSource.java`
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/HikerMobileProfileFetcher.java` (collect 가시성만)
- Modify: `crawler/src/main/java/com/celfit/crawler/dashboard/application/JobCostEstimator.java` (switch가 exhaustive라 case 추가 필수)
- Create: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/SelfWithHikerFallbackProfileFetcher.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/SelfWithHikerFallbackProfileFetcherTest.java`

**Interfaces:**
- Consumes: Task 2의 `SelfProfileFetcher.collect(usernames, badRequestOut)`, `HikerMobileProfileFetcher.collect(usernames)`(이 태스크에서 private → 패키지 가시성).
- Produces: `ProfileSource.SELF_HIKER_FALLBACK` enum 값(Task 4·5가 사용), `@Component SelfWithHikerFallbackProfileFetcher` — `ProfileSourceSelector`의 `List<ProfileFetcher>` 주입에 자동 등록되므로 셀렉터는 수정 불필요.

- [ ] **Step 1: 실패하는 테스트 작성** — 새 파일 `SelfWithHikerFallbackProfileFetcherTest.java`:

```java
package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
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
        var hiker = new HikerMobileProfileFetcher(http, passthrough(), om);
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
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :crawler:test --tests 'com.celfit.crawler.crawling.application.service.SelfWithHikerFallbackProfileFetcherTest'`
Expected: FAIL — `SELF_HIKER_FALLBACK`·컴포지트 클래스 없음(컴파일 에러).

- [ ] **Step 3: 구현**

(1) `ProfileSource.java`:

```java
public enum ProfileSource { SELF, ACTOR, HIKER_MOBILE, HIKER_WEB_GQL, DATALIKERS, SELF_HIKER_FALLBACK }
```

(2) `HikerMobileProfileFetcher.java` — `private ApifyResult collect(…)`의 `private` 제거(패키지 가시성) + 주석 추가:

```java
    /** 컴포지트(SELF_HIKER_FALLBACK)의 400 폴백 경로에서도 직접 호출된다 — 패키지 가시성. */
    ApifyResult collect(List<String> usernames) {
```

(3) `JobCostEstimator.profileRequestsPerAccount`의 switch에 case 추가(`case ACTOR` 앞):

```java
            case SELF_HIKER_FALLBACK -> {
                endpoints.add("instagram web_profile_info (self, 무료) — 400 계정만 HikerAPI /v2/user/by/username 폴백(+건당 1회)");
                yield 0;   // 폴백 비율은 사전 추정 불가 — 기본 0으로 집계
            }
```

(4) 새 파일 `SelfWithHikerFallbackProfileFetcher.java`:

```java
package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SELF 베이스 + 400 폴백 컴포지트 — web_profile_info로 배치를 돌리고, IP 무관 HTTP 400
 * (비즈니스 카테고리 버그)이 난 계정만 HikerAPI /v2/user/by/username으로 2차 조회해 병합한다.
 * 호출자가 ex.runId()로 raw를 저장하므로 crawl_run은 컴포지트 라벨로 1건만 만든다 —
 * 두 페처의 fetch()가 아니라 collect 로직을 직접 호출하는 이유. 혼합 배치의 아이템별
 * 실제 소스는 ProfileExtractor.detect로 구분한다.
 */
@Component
public class SelfWithHikerFallbackProfileFetcher implements ProfileFetcher {

    static final String LABEL = "profile-self-hiker";
    private static final Logger log = LoggerFactory.getLogger(SelfWithHikerFallbackProfileFetcher.class);

    private final SelfProfileFetcher self;
    private final HikerMobileProfileFetcher hiker;
    private final CrawlExecutor executor;

    public SelfWithHikerFallbackProfileFetcher(SelfProfileFetcher self, HikerMobileProfileFetcher hiker,
                                               CrawlExecutor executor) {
        this.self = self;
        this.hiker = hiker;
        this.executor = executor;
    }

    @Override
    public CrawlExecutor.Execution fetch(JobName job, List<String> usernames, TriggerType trigger) {
        return executor.execute(job, trigger, null, null, LABEL, () -> collect(usernames));
    }

    private ApifyResult collect(List<String> usernames) {
        List<String> badRequest = new ArrayList<>();
        ApifyResult base = self.collect(usernames, badRequest);
        if (badRequest.isEmpty()) return base;
        log.info("SELF 400 {}건 — Hiker 폴백: {}", badRequest.size(), badRequest);
        ApifyResult fallback = hiker.collect(badRequest);
        List<Map<String, Object>> items = new ArrayList<>(base.items());
        items.addAll(fallback.items());
        List<String> notFound = new ArrayList<>(base.notFound());
        notFound.addAll(fallback.notFound());
        return new ApifyResult(null, items, notFound);
    }

    @Override
    public ProfileSource source() {
        return ProfileSource.SELF_HIKER_FALLBACK;
    }

    /** 혼합 배치의 기본 소스 — 아이템별 실제 소스는 ProfileExtractor.detect로 구분한다. */
    @Override
    public RawSource rawSource() {
        return RawSource.SELF_GQL;
    }
}
```

- [ ] **Step 4: 통과 확인 (모듈 전체 — enum 추가로 깨지는 곳이 없는지 확인)**

Run: `./gradlew :crawler:test`
Expected: PASS (JobCostEstimator switch는 case를 추가했으므로 컴파일 OK)

- [ ] **Step 5: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/settings/domain/ProfileSource.java crawler/src/main/java/com/celfit/crawler/crawling/application/service/HikerMobileProfileFetcher.java crawler/src/main/java/com/celfit/crawler/dashboard/application/JobCostEstimator.java crawler/src/main/java/com/celfit/crawler/crawling/application/service/SelfWithHikerFallbackProfileFetcher.java crawler/src/test/java/com/celfit/crawler/crawling/application/service/SelfWithHikerFallbackProfileFetcherTest.java
git commit -m "feat(crawler): SELF_HIKER_FALLBACK 컴포지트 페처 — 400 계정만 Hiker 폴백"
```

---

### Task 4: 소비처 아이템별 소스 감지 적용 (CollectJob·QualifyJob·ProfileSupplementer)

**Files:**
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/CollectJob.java:248-274` (refreshProfile)
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/QualifyJob.java:146-174` (applyChunk)
- Modify: `crawler/src/main/java/com/celfit/crawler/crawling/application/service/ProfileSupplementer.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/CollectJobTest.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/QualifyJobTest.java`
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/application/service/ProfileSupplementerTest.java`

**Interfaces:**
- Consumes: Task 1의 `ProfileExtractor.detect(payload, defaultSource)`, Task 3의 `ProfileSource.SELF_HIKER_FALLBACK`.
- Produces: 없음(내부 소비처 수정). `RawProfile.source`에 아이템별 감지값이 저장된다.

- [ ] **Step 1: 실패하는 테스트 작성 (3파일)**

`QualifyJobTest`에 추가 (import 추가: `org.mockito.ArgumentCaptor`, `java.util.stream.Collectors`, `com.celfit.crawler.crawling.domain.RawProfile` — 기존 import에 없으면):

```java
@Test
void 혼합_배치는_아이템별_감지된_소스로_raw_profile을_저장한다() {
    when(settings.qualifyBatchLimit()).thenReturn(50);
    Influencer selfInf = influencer(1L, "self_user", InfluencerStatus.DISCOVERED, null, null);
    Influencer hikerInf = influencer(2L, "hiker_user", InfluencerStatus.DISCOVERED, null, null);
    when(influencers.findByStatusAndFollowersIsNull(eq(InfluencerStatus.DISCOVERED), any(Pageable.class)))
            .thenReturn(List.of(selfInf, hikerInf));
    when(selector.currentSource()).thenReturn(RawSource.SELF_GQL);   // 컴포지트의 기본 소스
    Map<String, Object> selfItem = Map.of("data", Map.of("user", Map.of(
            "username", "self_user", "id", "111", "edge_followed_by", Map.of("count", 5000))));
    when(selector.fetchAndSupplement(any(), any(), any()))
            .thenReturn(new CrawlExecutor.Execution(1L,
                    List.of(selfItem, hikerItem("hiker_user", 6000, "222"))));

    job.run(TriggerType.MANUAL, false);

    ArgumentCaptor<RawProfile> captor = ArgumentCaptor.forClass(RawProfile.class);
    verify(rawProfiles, org.mockito.Mockito.times(2)).save(captor.capture());
    Map<Long, RawSource> byInf = captor.getAllValues().stream()
            .collect(Collectors.toMap(RawProfile::getInfluencerId, RawProfile::getSource));
    assertThat(byInf.get(1L)).isEqualTo(RawSource.SELF_GQL);
    assertThat(byInf.get(2L)).isEqualTo(RawSource.HIKER_MOBILE);
    assertThat(selfInf.getFollowers()).isEqualTo(5000L);   // SELF 원형 경로로 추출됨
    assertThat(hikerInf.getFollowers()).isEqualTo(6000L);  // HIKER 원형 경로로 추출됨
    assertThat(hikerInf.getIgUserId()).isEqualTo("222");
}
```

`CollectJobTest`에 추가 (import 추가: `org.mockito.ArgumentCaptor`는 이미 있음 — 46행):

```java
/** HIKER_MOBILE 원형 — 폴백으로 넘어온 계정의 방문 케이스용. */
static Map<String, Object> hikerFallbackItem(String username, long followers, String pk) {
    Map<String, Object> user = new LinkedHashMap<>();
    user.put("username", username);
    user.put("follower_count", followers);
    user.put("pk", pk);
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("user", user);
    return root;
}

@Test
void 폴백으로_넘어온_프로필은_감지된_소스로_저장되고_추출된다() {
    wireCommon();
    Influencer inf = influencer(1L, "bugged_user", null, null);
    when(influencers.findCollectTargets(any(), any(PageRequest.class))).thenReturn(List.of(inf));
    when(profileSourceSelector.currentSource()).thenReturn(RawSource.SELF_GQL);   // 컴포지트 기본 소스
    when(profileSourceSelector.fetchAndSupplement(
            eq(JobName.COLLECT), eq(List.of("bugged_user")), eq(TriggerType.MANUAL)))
            .thenReturn(new CrawlExecutor.Execution(1L,
                    List.of(hikerFallbackItem("bugged_user", 7000L, "333"))));

    var summary = job().run(TriggerType.MANUAL);

    assertThat(summary.visited()).isEqualTo(1);
    ArgumentCaptor<RawProfile> captor = ArgumentCaptor.forClass(RawProfile.class);
    verify(rawProfiles).save(captor.capture());
    assertThat(captor.getValue().getSource()).isEqualTo(RawSource.HIKER_MOBILE);
    assertThat(inf.getFollowers()).isEqualTo(7000L);
    assertThat(inf.getIgUserId()).isEqualTo("333");   // 내장 타임라인 없음 → 미디어 폴백 재료
}
```

`ProfileSupplementerTest`에 추가 (기존 `selfItem()`·`hikerMobileItem()` 헬퍼 재사용):

```java
@Test void SELF_HIKER_FALLBACK_혼합_배치는_아이템별_감지로_보충한다() {
    java.util.List<String> paths = new java.util.ArrayList<>();
    HikerHttp http = path -> {
        paths.add(path);
        return "{\"users\":[{\"username\":\"my_zipcode\",\"pk\":\"1\"}]}";
    };
    var sup = new ProfileSupplementer(new HikerSuggestedSupplement(http, om), settingRelated(true));

    var ex = new CrawlExecutor.Execution(1L, List.of(selfItem(), hikerMobileItem()));
    sup.apply(ex, ProfileSource.SELF_HIKER_FALLBACK);

    // SELF 원형(data.user.id=999)과 HIKER 원형(user.pk=999) 모두 userId가 추출돼 보충된다
    assertThat(ex.items().get(0)).containsKeys("relatedProfiles", "_rawSuggested");
    assertThat(ex.items().get(1)).containsKeys("relatedProfiles", "_rawSuggested");
    assertThat(paths).hasSize(2).allSatisfy(p -> assertThat(p).contains("user_id=999"));
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :crawler:test --tests '*QualifyJobTest' --tests '*CollectJobTest' --tests '*ProfileSupplementerTest'`
Expected: FAIL — 혼합 아이템이 배치 고정 소스로 추출돼 username 매칭 실패(QualifyJob은 hiker_user 미저장, CollectJob은 "프로필 응답에 계정 없음" ApifyException으로 방문 실패, Supplementer는 두 번째 아이템 userId 추출 실패).

- [ ] **Step 3: 구현**

(1) `CollectJob.refreshProfile` — 배치 소스를 기본값으로 두고 아이템별 감지 (`source` 지역변수를 루프 안으로):

```java
    private Map<String, Object> refreshProfile(Influencer inf, TriggerType trigger) {
        RawSource batchSource = profileSourceSelector.currentSource();
        CrawlExecutor.Execution ex = profileSourceSelector.fetchAndSupplement(
                JobName.COLLECT, List.of(inf.getUsername()), trigger);
        if (ex.notFound().contains(inf.getUsername())) {
            // 방문 트랜잭션 안에서 저장하면 이 예외로 롤백된다 — run 루프가 트랜잭션 밖에서 처리
            throw new NotFoundException("프로필 404 — 계정 소멸: " + inf.getUsername());
        }
        for (Map<String, Object> item : ex.items()) {
            // 컴포지트(400 → Hiker 폴백) 배치는 아이템별 원형이 섞인다 — 셰이프로 실제 소스 감지
            RawSource source = ProfileExtractor.detect(item, batchSource);
            String username = ProfileExtractor.username(item, source);
            if (username == null || !username.equals(inf.getUsername())) continue;
            RawProfile rp = new RawProfile(inf.getId(), ex.runId(), source, item, clock.instant());
            rp.setUsername(username);
            Long followers = ProfileExtractor.followers(item, source);
            rp.setFollowers(followers);
            rawProfiles.save(rp);
            inf.setFollowers(followers);
            inf.setLastProfiledAt(clock.instant());
            String userId = ProfileExtractor.userId(item, source);
            if (userId == null) {
                throw new ApifyException("프로필 userId 추출 실패 — 방문 재시도: " + inf.getUsername());
            }
            inf.setIgUserId(userId);   // REELS 잡의 pk 재료 — 백필
            return item;
        }
        throw new ApifyException("프로필 응답에 계정 없음(401 차단 등) — 방문 재시도: " + inf.getUsername());
    }
```

(참고: 폴백 아이템의 피드는 코드 변경 불필요 — `visit()`의 `MediaItemExtractor.hasEmbeddedTimeline(payload)`가 이미 payload 셰이프로 분기하므로, 내장 타임라인이 없는 HIKER 원형은 자동으로 기존 `HIKER_V1_MEDIAS` 보충 경로를 탄다.)

(2) `QualifyJob.applyChunk` — 같은 패턴. 시그니처의 `RawSource source` 파라미터명을 `batchSource`로 바꾸고 루프 안에서 감지:

```java
    /** 청크 1개 적용(트랜잭션 안) — raw 원형 저장 + followers·igUserId 백필. */
    private int applyChunk(List<Influencer> chunk, CrawlExecutor.Execution ex, RawSource batchSource) {
        Map<String, Influencer> byName = chunk.stream()
                .collect(Collectors.toMap(Influencer::getUsername, i -> i));
        int profiled = 0;
        // 404로 판명된 계정(삭제·개명) — 소프트 딜리트로 종결, 매 실행 재선정·재과금을 끊는다
        for (String gone : ex.notFound()) {
            Influencer inf = byName.get(gone);
            if (inf == null) continue;
            inf.setStatus(InfluencerStatus.DELETED);
            influencers.save(inf);
            log.info("qualify 계정 소멸(404) — DELETED: {}", gone);
        }
        for (Map<String, Object> item : ex.items()) {
            // 컴포지트(400 → Hiker 폴백) 배치는 아이템별 원형이 섞인다 — 셰이프로 실제 소스 감지
            RawSource source = ProfileExtractor.detect(item, batchSource);
            String username = ProfileExtractor.username(item, source);
            Influencer inf = username != null ? byName.get(username) : null;
            if (inf == null) continue;
            RawProfile rp = new RawProfile(inf.getId(), ex.runId(), source, item, clock.instant());
            rp.setUsername(username);
            rp.setFollowers(ProfileExtractor.followers(item, source));
            rawProfiles.save(rp);
            inf.setFollowers(rp.getFollowers());
            String userId = ProfileExtractor.userId(item, source);
            if (userId != null) inf.setIgUserId(userId);   // collect 열거 파라미터 — 폴백용 보존
            inf.setLastProfiledAt(clock.instant());
            influencers.save(inf);   // detached — 명시 save
            profiled++;
        }
        return profiled;
    }
```

(호출부 `profileMissing`의 `applyChunk(chunk, ex, source)`는 변수명만 그대로 — 시그니처 변경 없음이라 수정 불필요.)

(3) `ProfileSupplementer` — DEFICIENT에 새 소스 추가 + 아이템별 감지:

```java
    private static final Set<ProfileSource> DEFICIENT =
            Set.of(ProfileSource.SELF, ProfileSource.HIKER_MOBILE, ProfileSource.SELF_HIKER_FALLBACK);
```

```java
    public CrawlExecutor.Execution apply(CrawlExecutor.Execution ex, ProfileSource source) {
        if (!DEFICIENT.contains(source)) return ex;
        if (!setting.relatedEnabled()) return ex;
        // SELF 계열(SELF·SELF_HIKER_FALLBACK)의 기본 원형은 SELF_GQL — 폴백 혼합분은 아이템별 감지
        RawSource base = source == ProfileSource.HIKER_MOBILE ? RawSource.HIKER_MOBILE : RawSource.SELF_GQL;
        for (Map<String, Object> item : ex.items()) {
            String uid = ProfileExtractor.userId(item, ProfileExtractor.detect(item, base));
            try { suggested.enrich(item, uid); }
            catch (RuntimeException e) { log.warn("related 보충 실패 {}: {}", uid, e.getMessage()); }
        }
        return ex;
    }
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :crawler:test --tests '*QualifyJobTest' --tests '*CollectJobTest' --tests '*ProfileSupplementerTest'`
Expected: PASS (기존 테스트 포함 — APIFY_ACTOR·DATALIKERS 배치는 detect 게이트로 동작 불변)

- [ ] **Step 5: 커밋**

```bash
git add crawler/src/main/java/com/celfit/crawler/crawling/application/service/CollectJob.java crawler/src/main/java/com/celfit/crawler/crawling/application/service/QualifyJob.java crawler/src/main/java/com/celfit/crawler/crawling/application/service/ProfileSupplementer.java crawler/src/test/java/com/celfit/crawler/crawling/application/service/CollectJobTest.java crawler/src/test/java/com/celfit/crawler/crawling/application/service/QualifyJobTest.java crawler/src/test/java/com/celfit/crawler/crawling/application/service/ProfileSupplementerTest.java
git commit -m "feat(crawler): 프로필 소비처에 아이템별 소스 감지 적용 — 혼합 배치 raw 저장 정확화"
```

---

### Task 5: 어드민 UI 노출

**Files:**
- Modify: `crawler/src/main/resources/templates/settings.html:53-60` (프로필 수집 방식 라디오)
- Test: `crawler/src/test/java/com/celfit/crawler/crawling/adapter/in/web/ProfileSourceUiControllerTest.java`

**Interfaces:**
- Consumes: Task 3의 `ProfileSource.SELF_HIKER_FALLBACK` (컨트롤러는 `ProfileSource.valueOf` 사용이라 코드 수정 불필요 — 템플릿만).

- [ ] **Step 1: 실패하는 테스트 작성** — `ProfileSourceUiControllerTest`에 추가:

```java
@Test
void SELF_HIKER_FALLBACK_소스도_저장된다() throws Exception {
    mvc.perform(post("/ui/profile-source").param("source", "SELF_HIKER_FALLBACK"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/ui/settings"));
    assertThat(sourceSetting.current()).isEqualTo(ProfileSource.SELF_HIKER_FALLBACK);
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :crawler:test --tests '*ProfileSourceUiControllerTest'`
Expected: Task 3에서 enum이 이미 추가됐으므로 이 테스트는 **PASS할 수 있다** — 그 경우 라디오 추가(Step 3)만 하고 진행 (컨트롤러는 valueOf 기반이라 서버 쪽 변경이 원래 없음; 테스트는 회귀 방지용 고정).

- [ ] **Step 3: 템플릿 수정** — `settings.html`의 `SELF` 라디오(53-54행) 바로 아래에 추가:

```html
        <label class="check"><input type="radio" name="source" value="SELF_HIKER_FALLBACK"
            th:checked="${profileSource == 'SELF_HIKER_FALLBACK'}"/> 자체 크롤 + 400 계정만 Hiker 폴백</label>
```

- [ ] **Step 4: 통과 확인 (UI 스모크 포함)**

Run: `./gradlew :crawler:test --tests '*ProfileSourceUiControllerTest' --tests '*UiSmokeTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add crawler/src/main/resources/templates/settings.html crawler/src/test/java/com/celfit/crawler/crawling/adapter/in/web/ProfileSourceUiControllerTest.java
git commit -m "feat(crawler): 어드민 설정 UI에 SELF_HIKER_FALLBACK 소스 노출"
```

---

### Task 6: 최종 검증 + 문서 갱신

**Files:**
- Modify: `ARCHITECTURE.md` (§5 작업 트랙 표, §7 결정 기록)
- Modify: `docs/superpowers/specs/2026-07-26-profile-400-hiker-fallback-design.md` (상태 헤더만)
- Move: `docs/superpowers/plans/2026-07-26-profile-400-hiker-fallback.md` → `docs/superpowers/plans/archive/`

- [ ] **Step 1: 전체 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — 실패 시 원인 수정 후 재실행(성공 출력 확인 전에 완료 주장 금지).

- [ ] **Step 2: ARCHITECTURE.md 갱신** — §5 작업 트랙 표에 본 작업 행 추가(완료 표기), §7 결정 기록에 항목 추가. 형식은 기존 항목을 따르되 요지: "프로필 400 → Hiker 폴백: `SELF_HIKER_FALLBACK` 컴포지트 소스 추가. 400(IP 무관 버그) 계정만 HikerAPI 폴백, 혼합 배치는 `ProfileExtractor.detect` 셰이프 감지로 아이템별 소스 기록. 실제 전환은 `profile.source` 수동 UPDATE(사용자 결정)."

- [ ] **Step 3: 스펙 상태 헤더 갱신** — 첫 줄을 `> 상태: 🟢 활성 · ✅ 구현됨`으로 변경(내용 불변).

- [ ] **Step 4: 계획 문서 아카이브**

```bash
mkdir -p docs/superpowers/plans/archive
git mv docs/superpowers/plans/2026-07-26-profile-400-hiker-fallback.md docs/superpowers/plans/archive/
```

- [ ] **Step 5: 커밋**

```bash
git add ARCHITECTURE.md docs/superpowers/specs/2026-07-26-profile-400-hiker-fallback-design.md
git commit -m "docs: ARCHITECTURE §5·§7 프로필 400 Hiker 폴백 반영 + 계획 아카이브"
```

---

## 배포 후 운영 절차 (작업 범위 밖 — 사용자 실행)

전환 시점에 사용자가 직접:

```sql
UPDATE app_setting SET value = 'SELF_HIKER_FALLBACK' WHERE key = 'profile.source';
```

또는 어드민 UI(`/ui/settings`)에서 라디오 선택. 롤백은 `HIKER_MOBILE`(현행) 또는 `SELF`로 동일 경로.
