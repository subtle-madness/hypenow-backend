package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.JobName;
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

/**
 * HikerAPI 웹 gql base — username→pk(by/username)→web_profile_info(gql) 순으로 조회해
 * 게시물·related 번들 프로필을 정규화. by/username 또는 gql 어느 쪽이든 실패(ApifyException)하면
 * 목적(번들)을 달성할 수 없으므로 모바일 base로 폴백하지 않고 해당 계정을 통째로 스킵한다.
 */
@Component
public class HikerWebGqlProfileFetcher implements ProfileFetcher {

    static final String LABEL = "profile-hiker-webgql";
    private static final Logger log = LoggerFactory.getLogger(HikerWebGqlProfileFetcher.class);

    private final HikerHttp http;
    private final CrawlExecutor executor;
    private final ProfileMapper mapper;

    public HikerWebGqlProfileFetcher(HikerHttp http, CrawlExecutor executor, ProfileMapper mapper) {
        this.http = http;
        this.executor = executor;
        this.mapper = mapper;
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
                Map<String, Object> gql = fetchOne(u);
                if (gql != null && gql.get("username") != null) out.add(gql);
            } catch (ApifyException e) {
                log.warn("web_profile_info 실패, 계정 스킵: {} ({})", u, e.getMessage());
            }
        }
        return out;
    }

    private Map<String, Object> fetchOne(String username) {
        String enc = URLEncoder.encode(username, StandardCharsets.UTF_8);
        Map<String, Object> base = mapper.fromHikerUser(http.get("/v2/user/by/username?username=" + enc));
        Object uid = base.get("userId");
        if (uid == null) {
            log.warn("userId 없음, 계정 스킵: {}", username);
            return null;
        }
        return mapper.fromHikerUser(http.get("/gql/user/web_profile_info?user_id=" + uid));
    }

    @Override
    public ProfileSource source() {
        return ProfileSource.HIKER_WEB_GQL;
    }
}
