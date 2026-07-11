package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.adapter.out.instagram.DirectDetailProperties;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.DetailFetcher;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.ShortCodes;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.DetailSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 비로그인 GraphQL 자체 피드 상세. 청크당 lsd 1회 부트스트랩 후 shortCode별 POST(per-item skip). */
@Component
public class SelfFeedDetailFetcher implements DetailFetcher {

    static final String LABEL = "detail-self-feed";
    private static final String GRAPHQL_URL = "https://www.instagram.com/api/graphql";
    private static final String APP_ID = "936619743392459";
    private static final Logger log = LoggerFactory.getLogger(SelfFeedDetailFetcher.class);

    private final InstagramWebClient web;
    private final CrawlExecutor executor;
    private final DetailMapper mapper;
    private final Duration pageDelay;
    private final String docId;
    private final String friendlyName;
    private final ObjectMapper om;

    @Autowired
    public SelfFeedDetailFetcher(InstagramWebClient web, CrawlExecutor executor, DetailMapper mapper,
                                 DirectDetailProperties props, ObjectMapper om) {
        this(web, executor, mapper, props.pageDelay(), props.postDocId(), props.postFriendlyName(), om);
    }

    SelfFeedDetailFetcher(InstagramWebClient web, CrawlExecutor executor, DetailMapper mapper,
                          Duration pageDelay, String docId, String friendlyName, ObjectMapper om) {
        this.web = web;
        this.executor = executor;
        this.mapper = mapper;
        this.pageDelay = pageDelay;
        this.docId = docId;
        this.friendlyName = friendlyName;
        this.om = om;
    }

    @Override
    public CrawlExecutor.Execution fetch(List<String> shortCodes, ContentType type, TriggerType trigger) {
        return executor.execute(JobName.AGGREGATE, trigger, null, null, LABEL,
                () -> new ApifyResult(null, collect(shortCodes)));
    }

    List<Map<String, Object>> collect(List<String> shortCodes) {
        if (shortCodes.isEmpty()) return List.of();
        var pageResp = web.get(ShortCodes.postUrl(shortCodes.get(0)));   // 부트스트랩: lsd 1회
        if (pageResp.status() >= 300) throw new ApifyException("부트스트랩 페이지 " + pageResp.status());
        String lsd = HandshakeExtractor.lsdFrom(pageResp.body());

        List<Map<String, Object>> out = new ArrayList<>();
        for (String sc : shortCodes) {
            try {
                var resp = web.post(GRAPHQL_URL, graphqlBody(lsd, sc),
                        Map.of("x-ig-app-id", APP_ID, "x-fb-lsd", lsd));
                if (resp.status() >= 300) throw new ApifyException("graphql " + resp.status());
                Map<String, Object> d = mapper.fromSelfGraphql(resp.body());
                if (d.get("shortCode") != null) out.add(d);
            } catch (ApifyException e) {
                log.warn("피드 상세 실패, 스킵: {} ({})", sc, e.getMessage());
            }
            sleep();
        }
        return out;
    }

    private String graphqlBody(String lsd, String shortCode) {
        var vars = new LinkedHashMap<String, Object>();
        vars.put("shortcode", shortCode);
        String varsJson;
        try {
            varsJson = om.writeValueAsString(vars);
        } catch (JacksonException e) {
            throw new ApifyException("variables 직렬화 실패", e);
        }
        return "lsd=" + enc(lsd)
                + "&fb_api_req_friendly_name=" + enc(friendlyName)
                + "&doc_id=" + enc(docId)
                + "&variables=" + enc(varsJson);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private void sleep() {
        try {
            if (pageDelay != null && !pageDelay.isZero()) Thread.sleep(pageDelay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApifyException("중단됨", e);
        }
    }

    @Override public DetailSource source() { return DetailSource.SELF; }

    @Override public boolean supports(ContentType type) { return type == ContentType.FEED; }
}
