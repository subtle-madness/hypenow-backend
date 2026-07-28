package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.NotFoundException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * HikerAPI 모바일 base — username별 /v2/user/by/username 단건 조회. 응답 원형을 그대로 반환.
 * 자체 API라 인스타 프록시 스로틀이 없으므로 소폭 병렬(FETCH_CONCURRENCY)로 대량 스냅샷을
 * 가속한다 — 계정 단위 실패 격리는 유지(실패 계정만 스킵, 응답 순서는 의미 없음).
 */
@Component
public class HikerMobileProfileFetcher implements ProfileFetcher {

    static final String LABEL = "profile-hiker-mobile";
    /** 동시 요청 수 — Hiker rate limit을 자극하지 않는 보수적 수준. */
    static final int FETCH_CONCURRENCY = 4;
    private static final Logger log = LoggerFactory.getLogger(HikerMobileProfileFetcher.class);

    private final HikerHttp http;
    private final CrawlExecutor executor;
    private final ObjectMapper om;

    public HikerMobileProfileFetcher(HikerHttp http, CrawlExecutor executor, ObjectMapper om) {
        this.http = http;
        this.executor = executor;
        this.om = om;
    }

    @Override
    public CrawlExecutor.Execution fetch(JobName job, List<String> usernames, TriggerType trigger) {
        return executor.execute(job, trigger, null, null, LABEL, () -> collect(usernames));
    }

    /** 컴포지트(SELF_HIKER_FALLBACK)의 400 폴백 경로에서도 직접 호출된다 — 패키지 가시성. */
    ApifyResult collect(List<String> usernames) {
        List<Map<String, Object>> out = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> notFound = java.util.Collections.synchronizedList(new ArrayList<>());
        // close()가 제출된 작업 완료까지 대기(Java 21) — 청크 반환 시점에 결과가 전부 모여 있다
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(FETCH_CONCURRENCY)) {
            for (String u : usernames) {
                pool.submit(() -> {
                    try {
                        String enc = URLEncoder.encode(u, StandardCharsets.UTF_8);
                        Map<String, Object> p = readRoot(http.get("/v2/user/by/username?username=" + enc));
                        if (ProfileExtractor.username(p, RawSource.HIKER_MOBILE) != null) out.add(p);
                    } catch (NotFoundException e) {
                        // 계정 소멸(삭제·개명) — 재시도 무의미, 호출자가 소프트 딜리트한다
                        notFound.add(u);
                        log.info("by/username 404 — 계정 소멸: {}", u);
                    } catch (ApifyException e) {
                        log.warn("by/username 실패, 계정 스킵: {} ({})", u, e.getMessage());
                    }
                });
            }
        }
        return new ApifyResult(null, new ArrayList<>(out), new ArrayList<>(notFound));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readRoot(String json) {
        try {
            return om.readValue(json, Map.class);
        } catch (JacksonException e) {
            throw new ApifyException("프로필 JSON 파싱 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public ProfileSource source() {
        return ProfileSource.HIKER_MOBILE;
    }

    @Override
    public RawSource rawSource() {
        return RawSource.HIKER_MOBILE;
    }
}
