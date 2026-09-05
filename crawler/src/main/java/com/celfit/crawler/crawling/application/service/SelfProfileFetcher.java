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
     * 전제라 동시 요청도 IP가 분산돼 안전하다. 배치 단위 회로 차단은 없다(프로세스 수준 헬스
     * 게이트는 컴포지트 SelfWithHikerFallbackProfileFetcher가 담당 — blockedOut 참조).
     */
    private ApifyResult collect(List<String> usernames) {
        return collect(usernames, new ArrayList<>());
    }

    ApifyResult collect(List<String> usernames, List<String> badRequestOut) {
        return collect(usernames, badRequestOut, new ArrayList<>());
    }

    ApifyResult collect(List<String> usernames, List<String> badRequestOut, List<String> emptyOut) {
        return collect(usernames, badRequestOut, emptyOut, new ArrayList<>());
    }

    /**
     * 컴포지트(SELF_HIKER_FALLBACK)용 — HTTP 400이 난 계정을 badRequestOut에, 200이지만
     * 응답에 계정이 없는(user null) 계정을 emptyOut에, 블록(429·401·403)·전송오류가
     * BLOCK_MAX_ATTEMPTS까지 소진된 계정을 blockedOut에 수집한다. 세 부류 모두
     * items·notFound에 안 들어가고 스킵되며, 컴포지트가 폴백 여부를 판단하는 재료다.
     */
    ApifyResult collect(List<String> usernames, List<String> badRequestOut, List<String> emptyOut,
                        List<String> blockedOut) {
        List<Map<String, Object>> out = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> notFound = java.util.Collections.synchronizedList(new ArrayList<>());
        // 워커들이 동시에 add하므로 동기화 래핑 — 호출자는 일반 리스트를 넘겨도 된다
        List<String> badRequest = java.util.Collections.synchronizedList(badRequestOut);
        List<String> empty = java.util.Collections.synchronizedList(emptyOut);
        List<String> blocked = java.util.Collections.synchronizedList(blockedOut);
        int total = usernames.size();
        var done = new java.util.concurrent.atomic.AtomicInteger();
        // 1명(collect 방문 경로)은 풀 없이 즉시 처리 — 방문마다 스레드풀을 만들 이유가 없다
        if (total == 1) {
            fetchOne(usernames.get(0), total, done, out, notFound, badRequest, empty, blocked);
            return new ApifyResult(null, out, notFound);
        }
        // close()가 제출된 작업 완료까지 대기(Java 21) — 반환 시점에 결과가 전부 모여 있다
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(FETCH_CONCURRENCY)) {
            for (int w = 0; w < FETCH_CONCURRENCY; w++) {
                final int offset = w;
                pool.submit(() -> {
                    for (int idx = offset; idx < total; idx += FETCH_CONCURRENCY) {
                        fetchOne(usernames.get(idx), total, done, out, notFound, badRequest, empty, blocked);
                        sleep();
                    }
                });
            }
        }
        return new ApifyResult(null, out, notFound);
    }

    /**
     * 계정 1명 처리 — 계정 단위 격리. 블록 응답(429·401·403)과 전송 오류(ApifyException —
     * 407·타임아웃·JSON 파싱 실패 등)는 같은 재시도 규칙(최대 BLOCK_MAX_ATTEMPTS회, 재시도=새
     * exit IP)을 따르며, 소진되면 blockedOut에 담아 컴포지트가 Hiker로 폴백할 수 있게 한다.
     */
    private void fetchOne(String u, int total, java.util.concurrent.atomic.AtomicInteger done,
                          List<Map<String, Object>> out, List<String> notFound,
                          List<String> badRequest, List<String> empty, List<String> blocked) {
        int i = done.incrementAndGet();
        for (int attempt = 1; attempt <= BLOCK_MAX_ATTEMPTS; attempt++) {
            try {
                InstagramWebClient.Response res = web.get(URL + u);
                if (res.status() == 200) {
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
                    if (attempt < BLOCK_MAX_ATTEMPTS) {
                        // 로테이팅 프록시 전제 — 재시도가 곧 새 exit IP. 간격은 계정 간 딜레이와 동일.
                        log.info("프로필 ({}/{}) {} — HTTP {} 블록, 재시도 {}/{}",
                                i, total, u, res.status(), attempt + 1, BLOCK_MAX_ATTEMPTS);
                        sleep();
                        continue;
                    }
                    blocked.add(u);
                    log.warn("프로필 ({}/{}) {} — 스킵(HTTP {} 블록, {}회 시도 소진), 폴백 대상 수집",
                            i, total, u, res.status(), attempt);
                    return;
                }
                if (res.status() == 400) {
                    // IP 무관 400(비즈니스 카테고리 버그) — 재시도 무의미. 컴포지트가 Hiker로
                    // 폴백할 수 있게 수집만 하고 스킵한다.
                    badRequest.add(u);
                    log.info("프로필 ({}/{}) {} — HTTP 400(버그 계정) 스킵, 폴백 대상 수집", i, total, u);
                    return;
                }
                if (res.status() == 404) {
                    // 계정 소멸(삭제·개명) — 재시도 무의미, 호출자가 소프트 딜리트한다
                    notFound.add(u);
                    log.info("프로필 ({}/{}) {} — 계정 소멸(HTTP 404)", i, total, u);
                } else {
                    log.info("프로필 ({}/{}) {} — 스킵(HTTP {})", i, total, u, res.status());
                }
                return;
            } catch (ApifyException e) {
                // 전송 오류(프록시 커넥션 절단·407·타임아웃·JSON 파싱 실패 등) — 블록과 동일한
                // 재시도 규칙을 따른다: 로테이팅 프록시라 재시도가 곧 새 exit IP다.
                if (attempt < BLOCK_MAX_ATTEMPTS) {
                    log.info("프로필 ({}/{}) {} — 요청 실패, 재시도 {}/{} ({})",
                            i, total, u, attempt + 1, BLOCK_MAX_ATTEMPTS, e.getMessage());
                    sleep();
                    continue;
                }
                blocked.add(u);
                log.warn("프로필 ({}/{}) {} — 요청 실패 {}회 소진, 폴백 대상 수집 ({})",
                        i, total, u, attempt, e.getMessage());
                return;
            }
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
