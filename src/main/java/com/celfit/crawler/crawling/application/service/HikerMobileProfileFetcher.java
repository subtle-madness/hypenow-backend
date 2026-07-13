package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
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

/** HikerAPI 모바일 base — username별 /v2/user/by/username 단건 조회. 응답 원형을 그대로 반환. */
@Component
public class HikerMobileProfileFetcher implements ProfileFetcher {

    static final String LABEL = "profile-hiker-mobile";
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
    public CrawlExecutor.Execution fetch(List<String> usernames, TriggerType trigger) {
        return executor.execute(JobName.QUALIFY, trigger, null, null, LABEL,
                () -> new ApifyResult(null, collect(usernames)));
    }

    private List<Map<String, Object>> collect(List<String> usernames) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String u : usernames) {
            try {
                String enc = URLEncoder.encode(u, StandardCharsets.UTF_8);
                Map<String, Object> p = readRoot(http.get("/v2/user/by/username?username=" + enc));
                if (ProfileExtractor.username(p, RawSource.HIKER_MOBILE) != null) out.add(p);
            } catch (ApifyException e) {
                log.warn("by/username 실패, 계정 스킵: {} ({})", u, e.getMessage());
            }
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

    @Override
    public ProfileSource source() {
        return ProfileSource.HIKER_MOBILE;
    }

    @Override
    public RawSource rawSource() {
        return RawSource.HIKER_MOBILE;
    }
}
