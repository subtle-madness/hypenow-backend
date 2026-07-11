package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.instagram.DirectCommentProperties;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 비로그인 web_profile_info 자체크롤 기반 프로필 조회. 계정마다 GET 1회씩 순차 조회 후 정규화.
 */
@Component
public class SelfProfileFetcher implements ProfileFetcher {

    static final String LABEL = "profile-self";
    private static final String URL =
            "https://www.instagram.com/api/v1/users/web_profile_info/?username=";

    private final InstagramWebClient web;
    private final CrawlExecutor executor;
    private final ProfileMapper mapper;
    private final Duration pageDelay;

    @Autowired
    public SelfProfileFetcher(InstagramWebClient web, CrawlExecutor executor,
                              ProfileMapper mapper, DirectCommentProperties props) {
        this(web, executor, mapper, props.pageDelay());
    }

    SelfProfileFetcher(InstagramWebClient web, CrawlExecutor executor,
                       ProfileMapper mapper, Duration pageDelay) {
        this.web = web;
        this.executor = executor;
        this.mapper = mapper;
        this.pageDelay = pageDelay;
    }

    @Override
    public CrawlExecutor.Execution fetch(List<String> usernames, TriggerType trigger) {
        return executor.execute(JobName.QUALIFY, trigger, null, null, LABEL,
                () -> new ApifyResult(null, collect(usernames)));
    }

    private List<Map<String, Object>> collect(List<String> usernames) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String u : usernames) {
            InstagramWebClient.Response res = web.get(URL + u);
            if (res.status() == 200) {
                Map<String, Object> p = mapper.fromSelf(res.body());
                if (p.get("username") != null) out.add(p);
            }
            sleep();
        }
        return out;
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
}
