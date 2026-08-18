package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.instagram.DirectCommentProperties;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 비로그인 web_profile_info 자체크롤 기반 프로필 조회. 계정마다 GET 1회씩 순차 조회, 응답 원형을 그대로 반환.
 */
@Component
public class SelfProfileFetcher implements ProfileFetcher {

    static final String LABEL = "profile-self";
    /** 연속 rate-limit/블록 응답 회로 차단기 임계값 — 이만큼 연달아 나면 배치를 중단해 IP 스로틀·차단 심화를 막는다. */
    static final int RATE_LIMIT_STREAK_LIMIT = 5;
    /**
     * 계정당 블록(429·401·403) 최대 시도 횟수. 로테이팅 프록시는 요청마다 새 exit IP라 401은
     * "그 IP 하나가 차단됨"일 뿐 — 즉시 재시도가 곧 IP 교체다. 재시도 없이 스킵하면 방문 실패율이
     * IP 차단율만큼 치솟는다(운영 실측 07-22: APIFY 프록시 요청 ~37%가 401 → 방문 실패 홍수).
     * 직접 연결(DIRECT)에서도 회로 차단기(연속 카운터)가 그대로 살아 있어 시스템적 차단은 여전히 잡힌다.
     */
    static final int BLOCK_MAX_ATTEMPTS = 3;
    /** 동시 요청 수 — 프록시 로테이션(요청마다 새 exit IP) 전제. HikerMobileProfileFetcher와 동일 수준. */
    static final int FETCH_CONCURRENCY = 4;
    /** 인스타가 과도한 익명 요청에 주는 코드 — 429(소프트 rate limit) → 401/403(하드 블록)으로 에스컬레이션. */
    private static boolean isBlockStatus(int status) {
        return status == 429 || status == 401 || status == 403;
    }
    private static final Logger log = LoggerFactory.getLogger(SelfProfileFetcher.class);
    private static final String URL =
            "https://www.instagram.com/api/v1/users/web_profile_info/?username=";

    private final InstagramWebClient web;
    private final CrawlExecutor executor;
    private final ObjectMapper om;
    private final Duration pageDelay;

    @Autowired
    public SelfProfileFetcher(InstagramWebClient web, CrawlExecutor executor,
                              ObjectMapper om, DirectCommentProperties props) {
        this(web, executor, om, props.pageDelay());
    }

    SelfProfileFetcher(InstagramWebClient web, CrawlExecutor executor,
                       ObjectMapper om, Duration pageDelay) {
        this.web = web;
        this.executor = executor;
        this.om = om;
        this.pageDelay = pageDelay;
    }

    @Override
    public CrawlExecutor.Execution fetch(JobName job, List<String> usernames, TriggerType trigger) {
        return executor.execute(job, trigger, null, null, LABEL, () -> collect(usernames));
    }

    /**
     * FETCH_CONCURRENCY 워커가 계정을 나눠 순차 처리한다 — 프록시 로테이션(요청마다 새 exit IP)
     * 전제라 동시 요청도 IP가 분산돼 안전하다. 회로 차단은 워커들이 공유하는 연속 카운터로 판단하며,
     * 트립 후 최대 (동시성-1)건의 진행 중 요청은 마저 나갈 수 있다(중단 의미는 동일).
     */
    private ApifyResult collect(List<String> usernames) {
        return collect(usernames, new ArrayList<>());
    }

    ApifyResult collect(List<String> usernames, List<String> badRequestOut) {
        return collect(usernames, badRequestOut, new ArrayList<>());
    }

    /**
     * 컴포지트(SELF_HIKER_FALLBACK)용 — HTTP 400이 난 계정을 badRequestOut에, 200이지만
     * 응답에 계정이 없는(user null) 계정을 emptyOut에 수집한다. 수집 외 동작은 단독 SELF와
     * 동일(두 부류 모두 items·notFound에 안 들어가고 스킵).
     */
    ApifyResult collect(List<String> usernames, List<String> badRequestOut, List<String> emptyOut) {
        List<Map<String, Object>> out = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> notFound = java.util.Collections.synchronizedList(new ArrayList<>());
        // 워커들이 동시에 add하므로 동기화 래핑 — 호출자는 일반 리스트를 넘겨도 된다
        List<String> badRequest = java.util.Collections.synchronizedList(badRequestOut);
        List<String> empty = java.util.Collections.synchronizedList(emptyOut);
        int total = usernames.size();
        var done = new java.util.concurrent.atomic.AtomicInteger();
        var rateLimitStreak = new java.util.concurrent.atomic.AtomicInteger();
        var tripped = new java.util.concurrent.atomic.AtomicBoolean(false);
        // 1명(collect 방문 경로)은 풀 없이 즉시 처리 — 방문마다 스레드풀을 만들 이유가 없다
        if (total == 1) {
            fetchOne(usernames.get(0), total, done, rateLimitStreak, tripped, out, notFound, badRequest, empty);
            return new ApifyResult(null, out, notFound);
        }
        // close()가 제출된 작업 완료까지 대기(Java 21) — 반환 시점에 결과가 전부 모여 있다
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(FETCH_CONCURRENCY)) {
            for (int w = 0; w < FETCH_CONCURRENCY; w++) {
                final int offset = w;
                pool.submit(() -> {
                    for (int idx = offset; idx < total; idx += FETCH_CONCURRENCY) {
                        if (tripped.get()) return;   // 회로 트립 — 남은 계정은 다음 실행 재시도
                        fetchOne(usernames.get(idx), total, done, rateLimitStreak, tripped, out, notFound, badRequest, empty);
                        sleep();
                    }
                });
            }
        }
        return new ApifyResult(null, out, notFound);
    }

    /** 계정 1명 처리 — 계정 단위 격리(요청 예외는 해당 계정만 스킵). 블록 응답은 재시도(=새 IP)로 회복 시도. */
    private void fetchOne(String u, int total, java.util.concurrent.atomic.AtomicInteger done,
                          java.util.concurrent.atomic.AtomicInteger rateLimitStreak,
                          java.util.concurrent.atomic.AtomicBoolean tripped,
                          List<Map<String, Object>> out, List<String> notFound,
                          List<String> badRequest, List<String> empty) {
        int i = done.incrementAndGet();
        try {
            for (int attempt = 1; attempt <= BLOCK_MAX_ATTEMPTS; attempt++) {
                InstagramWebClient.Response res = web.get(URL + u);
                if (res.status() == 200) {
                    rateLimitStreak.set(0);   // 성공 = 스로틀 해소 신호, 회로 카운터 리셋
                    Map<String, Object> p = readRoot(res.body());
                    if (ProfileExtractor.username(p, RawSource.SELF_GQL) != null) {
                        out.add(p);
                        log.info("프로필 ({}/{}) {} — 확보", i, total, u);
                    } else {
                        // IG가 일부 계정을 익명 API에서 숨기는 케이스(200 + user null) —
                        // 컴포지트가 폴백을 판단할 수 있게 수집만 하고 스킵한다
                        empty.add(u);
                        log.info("프로필 ({}/{}) {} — 스킵(응답에 계정 없음), 폴백 후보 수집", i, total, u);
                    }
                    return;
                }
                if (isBlockStatus(res.status())) {
                    // rate limit/블록(429·401·403) — 연속으로 임계값에 도달하면 IP 차단으로 판단해 중단.
                    int streak = rateLimitStreak.incrementAndGet();
                    if (streak >= RATE_LIMIT_STREAK_LIMIT) {
                        tripped.set(true);
                        log.warn("프로필 블록(HTTP {}) 연속 {}회 — IP 차단으로 판단해 배치 중단(남은 {}명은 다음 실행 재시도)",
                                res.status(), streak, total - i);
                        return;
                    }
                    if (attempt < BLOCK_MAX_ATTEMPTS && !tripped.get()) {
                        // 로테이팅 프록시 전제 — 재시도가 곧 새 exit IP. 간격은 계정 간 딜레이와 동일.
                        log.info("프로필 ({}/{}) {} — HTTP {} 블록, 재시도 {}/{}",
                                i, total, u, res.status(), attempt + 1, BLOCK_MAX_ATTEMPTS);
                        sleep();
                        continue;
                    }
                    log.info("프로필 ({}/{}) {} — 스킵(HTTP {} rate limit/블록, {}회 시도 소진, 연속 {}회)",
                            i, total, u, res.status(), attempt, streak);
                    return;
                }
                if (res.status() == 400) {
                    // IP 무관 400(비즈니스 카테고리 버그) — 재시도 무의미. 컴포지트가 Hiker로
                    // 폴백할 수 있게 수집만 하고 스킵한다(블록 신호가 아니므로 회로 카운터 리셋).
                    rateLimitStreak.set(0);
                    badRequest.add(u);
                    log.info("프로필 ({}/{}) {} — HTTP 400(버그 계정) 스킵, 폴백 대상 수집", i, total, u);
                    return;
                }
                if (res.status() == 404) {
                    // 계정 소멸(삭제·개명) — 재시도 무의미, 호출자가 소프트 딜리트한다
                    rateLimitStreak.set(0);
                    notFound.add(u);
                    log.info("프로필 ({}/{}) {} — 계정 소멸(HTTP 404)", i, total, u);
                } else {
                    rateLimitStreak.set(0);
                    log.info("프로필 ({}/{}) {} — 스킵(HTTP {})", i, total, u, res.status());
                }
                return;
            }
        } catch (ApifyException e) {
            log.warn("프로필 ({}/{}) {} — 요청 실패, 해당 계정만 건너뜀 ({})", i, total, u, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readRoot(String json) {
        try {
            return om.readValue(json, Map.class);
        } catch (JacksonException e) {
            throw new ApifyException("프로필 JSON 파싱 실패: " + e.getMessage(), e);
        }
    }

    private void sleep() {
        if (pageDelay == null || pageDelay.isZero()) return;
        try {
            Thread.sleep(pageDelay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public ProfileSource source() {
        return ProfileSource.SELF;
    }

    @Override
    public RawSource rawSource() {
        return RawSource.SELF_GQL;
    }
}
