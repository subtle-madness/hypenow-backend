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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 비로그인 GraphQL 자체 댓글 크롤. 청크당 lsd를 1회 부트스트랩(무거운 페이지 GET 1회)한 뒤,
 * 각 포스트는 가벼운 GraphQL POST + 커서 페이지네이션으로만 수집(세션 재사용 → 바이트 절감).
 */
@Component
public class DirectCommentFetcher implements CommentFetcher {

    static final String ACTOR_LABEL = "direct-comment-crawler";
    private static final String GRAPHQL_URL = "https://www.instagram.com/api/graphql";
    private static final String APP_ID = "936619743392459";
    private static final Logger log = LoggerFactory.getLogger(DirectCommentFetcher.class);

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
        if (shortCodes.isEmpty()) return List.of();
        // 세션 부트스트랩: 무거운 포스트 페이지 GET(~600KB)을 청크당 딱 1회만 해서 lsd를 확보한다.
        // lsd는 특정 포스트가 아니라 익명 세션에 묶여 있어(실측 확인) 청크 전체 graphql에 재사용 가능하고,
        // 쿠키(csrftoken·mid)는 공유 CookieManager가 자동 유지한다. → 포스트당 페이지 GET 제거 = 전송 바이트 대폭 절감.
        var pageResp = web.get(ShortCodes.postUrl(shortCodes.get(0)));
        if (pageResp.status() >= 300) throw new ApifyException("부트스트랩 페이지 " + pageResp.status());
        String lsd = HandshakeExtractor.lsdFrom(pageResp.body());
        long pageBytes = utf8Len(pageResp.body());   // 부트스트랩 페이지 전송량(응답)

        long[] graphqlBytes = {0};                    // graphql 응답 전송량 누적
        List<Map<String, Object>> all = new ArrayList<>();
        for (String sc : shortCodes) {
            all.addAll(collectOne(sc, limit, lsd, graphqlBytes));
        }
        logTransfer(shortCodes.size(), all.size(), pageBytes, graphqlBytes[0]);
        return all;
    }

    /** run당 전송량을 로그로 남긴다. 최적화 전(페이지 GET을 포스트마다) 대비 절감비도 같이 표시. */
    private void logTransfer(int posts, int comments, long pageBytes, long graphqlBytes) {
        long actual = pageBytes + graphqlBytes;                 // 세션 재사용: 페이지 1회
        long naive = pageBytes * (long) posts + graphqlBytes;   // 최적화 전: 페이지 posts회
        String ratio = actual > 0 ? String.format("%.1f", (double) naive / actual) : "-";
        log.info("[direct-comment] 포스트 {}개·댓글 {}개 | 전송 실측 {}KB (페이지 {}KB×1 + graphql {}KB) "
                        + "| 세션재사용 없었다면 {}KB → {}배 절감",
                posts, comments, actual / 1024, pageBytes / 1024, graphqlBytes / 1024,
                naive / 1024, ratio);
    }

    private static long utf8Len(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    private List<Map<String, Object>> collectOne(String shortCode, int limit, String lsd, long[] graphqlBytes) {
        String postUrl = ShortCodes.postUrl(shortCode);
        long mediaId = HandshakeExtractor.mediaIdFromShortCode(shortCode);

        List<Map<String, Object>> out = new ArrayList<>();
        String cursor = null;
        while (out.size() < limit) {
            var resp = web.post(GRAPHQL_URL, graphqlBody(lsd, mediaId, cursor),
                    Map.of("x-ig-app-id", APP_ID, "x-fb-lsd", lsd));
            if (resp.status() >= 300) throw new ApifyException("graphql " + resp.status());
            graphqlBytes[0] += utf8Len(resp.body());
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
