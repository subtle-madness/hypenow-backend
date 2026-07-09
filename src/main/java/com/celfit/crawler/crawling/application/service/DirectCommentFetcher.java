package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.instagram.DirectCommentProperties;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.CommentFetcher;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.ShortCodes;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.CommentSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 비로그인 GraphQL 자체 댓글 크롤. 포스트별로 세션 GET → lsd 추출 → GraphQL POST → 페이지네이션. */
@Component
public class DirectCommentFetcher implements CommentFetcher {

    static final String ACTOR_LABEL = "direct-comment-crawler";
    private static final String GRAPHQL_URL = "https://www.instagram.com/api/graphql";
    private static final String APP_ID = "936619743392459";

    private final InstagramWebClient web;
    private final CrawlExecutor executor;
    private final CommentMapper mapper;
    private final Duration pageDelay;
    private final String docId;
    private final String friendlyName;

    @org.springframework.beans.factory.annotation.Autowired
    public DirectCommentFetcher(InstagramWebClient web, CrawlExecutor executor,
                                CommentMapper mapper, DirectCommentProperties props) {
        this(web, executor, mapper, props.pageDelay(), props.commentDocId(), props.commentFriendlyName());
    }

    DirectCommentFetcher(InstagramWebClient web, CrawlExecutor executor, CommentMapper mapper,
                         Duration pageDelay, String docId, String friendlyName) {
        this.web = web;
        this.executor = executor;
        this.mapper = mapper;
        this.pageDelay = pageDelay;
        this.docId = docId;
        this.friendlyName = friendlyName;
    }

    @Override
    public CrawlExecutor.Execution fetch(List<String> shortCodes, int limit, TriggerType trigger) {
        return executor.execute(JobName.AGGREGATE, trigger, null, null, ACTOR_LABEL,
                () -> new ApifyResult(null, collectAll(shortCodes, limit)));
    }

    private List<Map<String, Object>> collectAll(List<String> shortCodes, int limit) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (String sc : shortCodes) {
            all.addAll(collectOne(sc, limit));
        }
        return all;
    }

    private List<Map<String, Object>> collectOne(String shortCode, int limit) {
        String postUrl = ShortCodes.postUrl(shortCode);
        var pageResp = web.get(postUrl);
        if (pageResp.status() >= 300) throw new ApifyException("포스트 페이지 " + pageResp.status());
        String lsd = HandshakeExtractor.lsdFrom(pageResp.body());
        long mediaId = HandshakeExtractor.mediaIdFromShortCode(shortCode);

        List<Map<String, Object>> out = new ArrayList<>();
        String cursor = null;
        while (out.size() < limit) {
            var resp = web.post(GRAPHQL_URL, graphqlBody(lsd, mediaId, cursor),
                    Map.of("x-ig-app-id", APP_ID, "x-fb-lsd", lsd));
            if (resp.status() >= 300) throw new ApifyException("graphql " + resp.status());
            var page = mapper.parse(resp.body(), postUrl);
            out.addAll(page.comments());
            if (!page.hasNext() || page.endCursor() == null) break;
            cursor = page.endCursor();
            sleep();
        }
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** variables 정확한 키/형식은 Task 12 스모크에서 실측 cURL로 확정. */
    private String graphqlBody(String lsd, long mediaId, String cursor) {
        StringBuilder vars = new StringBuilder("{\"media_id\":\"").append(mediaId).append("\"");
        if (cursor != null) vars.append(",\"after\":\"").append(cursor).append("\"");
        vars.append("}");
        return "lsd=" + enc(lsd)
                + "&fb_api_req_friendly_name=" + enc(friendlyName)
                + "&doc_id=" + enc(docId)
                + "&variables=" + enc(vars.toString());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private void sleep() {
        try {
            if (!pageDelay.isZero()) Thread.sleep(pageDelay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApifyException("중단됨", e);
        }
    }

    @Override
    public CommentSource source() {
        return CommentSource.DIRECT;
    }
}
