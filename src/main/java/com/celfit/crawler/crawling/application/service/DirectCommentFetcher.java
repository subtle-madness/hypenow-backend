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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 비로그인 GraphQL 자체 댓글 크롤. 포스트별로 세션 GET → lsd 추출 → GraphQL POST → 페이지네이션. */
@Component
public class DirectCommentFetcher implements CommentFetcher {

    static final String ACTOR_LABEL = "direct-comment-crawler";
    private static final String GRAPHQL_URL = "https://www.instagram.com/api/graphql";
    private static final String APP_ID = "936619743392459";

    private final InstagramWebClient web;
    private final CrawlExecutor executor;
    private final CommentMapper mapper;
    private final ObjectMapper om;
    private final Duration pageDelay;
    private final String docId;
    private final String friendlyName;

    @org.springframework.beans.factory.annotation.Autowired
    public DirectCommentFetcher(InstagramWebClient web, CrawlExecutor executor,
                                CommentMapper mapper, DirectCommentProperties props, ObjectMapper om) {
        this(web, executor, mapper, props.pageDelay(), props.commentDocId(), props.commentFriendlyName(), om);
    }

    DirectCommentFetcher(InstagramWebClient web, CrawlExecutor executor, CommentMapper mapper,
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
            String next = page.endCursor();
            // 무진행 방어: 서버가 hasNext=true인데 새 댓글이 없거나 커서가 안 바뀌면 종료(무한루프 방지)
            if (!page.hasNext() || next == null || next.equals(cursor) || page.comments().isEmpty()) break;
            cursor = next;
            sleep();
        }
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /**
     * variables 정확한 키/형식은 Task 12 스모크에서 실측 cURL로 확정.
     * 커서(cursor)는 IG가 내려주는 중첩 JSON 문자열(따옴표 포함)이므로 문자열 연결이 아닌
     * ObjectMapper 직렬화로 이스케이핑해야 한다(Task 12 실측 재확인에서 발견된 버그 수정).
     */
    private String graphqlBody(String lsd, long mediaId, String cursor) {
        var vars = new LinkedHashMap<String, Object>();
        vars.put("media_id", String.valueOf(mediaId));
        if (cursor != null) vars.put("after", cursor);
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
