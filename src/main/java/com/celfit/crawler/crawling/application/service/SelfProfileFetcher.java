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
    /** 연속 429(rate limit) 회로 차단기 임계값 — 이만큼 연달아 나면 배치를 중단해 IP 스로틀 심화·차단을 막는다. */
    static final int RATE_LIMIT_STREAK_LIMIT = 5;
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
        return executor.execute(job, trigger, null, null, LABEL,
                () -> new ApifyResult(null, collect(usernames)));
    }

    private List<Map<String, Object>> collect(List<String> usernames) {
        List<Map<String, Object>> out = new ArrayList<>();
        int total = usernames.size(), i = 0, rateLimitStreak = 0;
        for (String u : usernames) {
            i++;
            // 계정 단위 격리 — 프록시가 커넥션을 중간에 끊으면(TLS BUFFER_UNDERFLOW 등) 요청이
            // 예외로 죽는데, 그 1명 때문에 청크 전체를 실패시키지 않는다. 빠진 계정은 응답에
            // 없으므로 호출자(qualify deferred / collect 저장 userId 폴백)가 재시도를 담당한다.
            try {
                InstagramWebClient.Response res = web.get(URL + u);
                if (res.status() == 200) {
                    rateLimitStreak = 0;   // 성공 = 스로틀 해소 신호, 회로 카운터 리셋
                    Map<String, Object> p = readRoot(res.body());
                    if (ProfileExtractor.username(p, RawSource.SELF_GQL) != null) {
                        out.add(p);
                        log.info("프로필 ({}/{}) {} — 확보", i, total, u);
                    } else {
                        log.info("프로필 ({}/{}) {} — 스킵(응답에 계정 없음)", i, total, u);
                    }
                } else if (res.status() == 429) {
                    // rate limit — 연속으로 임계값에 도달하면 IP 스로틀로 판단, 남은 계정은 두드리지 않고 중단.
                    if (++rateLimitStreak >= RATE_LIMIT_STREAK_LIMIT) {
                        log.warn("프로필 429 연속 {}회 — IP 스로틀로 판단해 배치 중단(남은 {}명은 다음 실행 재시도)",
                                rateLimitStreak, total - i);
                        break;
                    }
                    log.info("프로필 ({}/{}) {} — 스킵(429 rate limit, 연속 {}회)", i, total, u, rateLimitStreak);
                } else {
                    rateLimitStreak = 0;
                    log.info("프로필 ({}/{}) {} — 스킵(HTTP {})", i, total, u, res.status());
                }
            } catch (ApifyException e) {
                log.warn("프로필 ({}/{}) {} — 요청 실패, 해당 계정만 건너뜀 ({})", i, total, u, e.getMessage());
            }
            sleep();
        }
        return out;
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
