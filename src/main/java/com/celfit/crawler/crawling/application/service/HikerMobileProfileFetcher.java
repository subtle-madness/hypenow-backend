package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
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
import org.springframework.stereotype.Component;

/** HikerAPI 모바일 base — username별 /v2/user/by/username 단건 조회로 프로필 정규화. */
@Component
public class HikerMobileProfileFetcher implements ProfileFetcher {

    static final String LABEL = "profile-hiker-mobile";

    private final HikerHttp http;
    private final CrawlExecutor executor;
    private final ProfileMapper mapper;

    public HikerMobileProfileFetcher(HikerHttp http, CrawlExecutor executor, ProfileMapper mapper) {
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
            String enc = URLEncoder.encode(u, StandardCharsets.UTF_8);
            Map<String, Object> p = mapper.fromHikerUser(http.get("/v2/user/by/username?username=" + enc));
            if (p.get("username") != null) out.add(p);
        }
        return out;
    }

    @Override
    public ProfileSource source() {
        return ProfileSource.HIKER_MOBILE;
    }
}
