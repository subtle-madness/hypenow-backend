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

/**
 * HikerAPI 웹 gql base — username→pk(by/username)→web_profile_info(gql) 순으로 조회한다.
 * by/username 또는 gql 어느 쪽이든 실패(ApifyException)하면 목적(번들)을 달성할 수 없으므로
 * 모바일 base로 폴백하지 않고 해당 계정을 통째로 스킵한다. 두 응답 모두 HikerAPI user 객체
 * 원형(by/username과 동일 형태)이라 rawSource()는 HIKER_MOBILE과 같다.
 */
@Component
public class HikerWebGqlProfileFetcher implements ProfileFetcher {

    static final String LABEL = "profile-hiker-webgql";
    private static final Logger log = LoggerFactory.getLogger(HikerWebGqlProfileFetcher.class);

    private final HikerHttp http;
    private final CrawlExecutor executor;
    private final ObjectMapper om;

    public HikerWebGqlProfileFetcher(HikerHttp http, CrawlExecutor executor, ObjectMapper om) {
        this.http = http;
        this.executor = executor;
        this.om = om;
    }

    @Override
    public CrawlExecutor.Execution fetch(JobName job, List<String> usernames, TriggerType trigger) {
        return executor.execute(job, trigger, null, null, LABEL,
                () -> new ApifyResult(null, collect(usernames)));
    }

    private List<Map<String, Object>> collect(List<String> usernames) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String u : usernames) {
            try {
                Map<String, Object> gql = fetchOne(u);
                if (gql != null && ProfileExtractor.username(gql, RawSource.HIKER_MOBILE) != null) out.add(gql);
            } catch (ApifyException e) {
                log.warn("web_profile_info 실패, 계정 스킵: {} ({})", u, e.getMessage());
            }
        }
        return out;
    }

    private Map<String, Object> fetchOne(String username) {
        String enc = URLEncoder.encode(username, StandardCharsets.UTF_8);
        Map<String, Object> base = readRoot(http.get("/v2/user/by/username?username=" + enc));
        String uid = ProfileExtractor.userId(base, RawSource.HIKER_MOBILE);
        if (uid == null) {
            log.warn("userId 없음, 계정 스킵: {}", username);
            return null;
        }
        return readRoot(http.get("/gql/user/web_profile_info?user_id=" + uid));
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
        return ProfileSource.HIKER_WEB_GQL;
    }

    @Override
    public RawSource rawSource() {
        return RawSource.HIKER_MOBILE;
    }
}
