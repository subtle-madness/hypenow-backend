package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.instagram.DirectCommentProperties;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.CommentFetcher;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawSource;
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
    public CommentResult fetch(List<String> shortCodes, int commentsPerPost, TriggerType trigger) {
        Map<String, List<Map<String, Object>>> pagesByCode = new LinkedHashMap<>();
        CrawlExecutor.Execution ex = executor.execute(JobName.COLLECT, trigger, null, null, ACTOR_LABEL,
                () -> collectAll(shortCodes, commentsPerPost, pagesByCode));
        return new CommentResult(ex.runId(), pagesByCode);
    }

    /** 포스트 1개분 수집 결과 — 저장용 페이지 원형 목록 + 로그용 실제 댓글 수. */
    private record Collected(List<Map<String, Object>> pages, int commentCount) {}

    private ApifyResult collectAll(List<String> shortCodes, int limit,
                                   Map<String, List<Map<String, Object>>> pagesByCode) {
        if (shortCodes.isEmpty()) return new ApifyResult(null, List.of());
        // 세션 부트스트랩: 무거운 포스트 페이지 GET(~600KB)을 청크당 딱 1회만 해서 lsd를 확보한다.
        // lsd는 특정 포스트가 아니라 익명 세션에 묶여 있어(실측 확인) 청크 전체 graphql에 재사용 가능하고,
        // 쿠키(csrftoken·mid)는 공유 CookieManager가 자동 유지한다. → 포스트당 페이지 GET 제거 = 전송 바이트 대폭 절감.
        var pageResp = web.get(ShortCodes.postUrl(shortCodes.get(0)));
        if (pageResp.status() >= 300) throw new ApifyException("부트스트랩 페이지 " + pageResp.status());
        String lsd = HandshakeExtractor.lsdFrom(pageResp.body());
        long pageBytes = utf8Len(pageResp.body());   // 부트스트랩 페이지 전송량(응답)

        long[] graphqlBytes = {0};                    // graphql 응답 전송량 누적
        List<Map<String, Object>> allPages = new ArrayList<>();
        int totalComments = 0;
        int failedPosts = 0;
        // 포스트 1개 실패가 청크 전체 run을 실패시키지 않도록 격리한다 — 실패한 shortCode는
        // pagesByCode에 키를 남기지 않아(호출자가 null로 판별) 그 게시물만 재시도 대상이 되고,
        // 이미 성공한 게시물의 페이지는 이번 run 결과로 그대로 저장된다.
        for (String sc : shortCodes) {
            try {
                Collected c = collectOne(sc, limit, lsd, graphqlBytes);
                pagesByCode.put(sc, c.pages());
                allPages.addAll(c.pages());
                totalComments += c.commentCount();
            } catch (RuntimeException e) {
                failedPosts++;
                log.warn("[direct-comment] 포스트 {} 수집 실패 — 격리 후 다음 포스트 계속: {}", sc, e.getMessage());
            }
        }
        if (failedPosts > 0) {
            log.info("[direct-comment] 포스트 {}개 중 {}개 실패 — 해당 shortCode만 다음 방문 재시도 대상",
                    shortCodes.size(), failedPosts);
        }
        logTransfer(shortCodes.size(), totalComments, pageBytes, graphqlBytes[0]);
        return new ApifyResult(null, allPages);
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

    /**
     * 커서 페이지네이션으로 포스트 1개분을 수집. CommentMapper.parse()는 커서·hasNext 판단(및
     * 로그용 댓글 수 계산)에만 쓰고, 저장용으로는 응답 JSON 원형(om.readValue)을 페이지 단위로 쌓는다.
     */
    private Collected collectOne(String shortCode, int limit, String lsd, long[] graphqlBytes) {
        String postUrl = ShortCodes.postUrl(shortCode);
        long mediaId = HandshakeExtractor.mediaIdFromShortCode(shortCode);

        List<Map<String, Object>> pages = new ArrayList<>();
        String cursor = null;
        int collected = 0;
        while (collected < limit) {
            var resp = web.post(GRAPHQL_URL, graphqlBody(lsd, mediaId, cursor),
                    Map.of("x-ig-app-id", APP_ID, "x-fb-lsd", lsd));
            if (resp.status() >= 300) throw new ApifyException("graphql " + resp.status());
            graphqlBytes[0] += utf8Len(resp.body());
            var page = mapper.parse(resp.body(), postUrl);
            // 무진행 방어: 새 댓글이 없으면 종료(무한루프 방지)
            if (page.comments().isEmpty()) break;
            pages.add(rawPage(resp.body()));
            collected += page.comments().size();
            String next = page.endCursor();
            // 무진행 방어: 서버가 hasNext=true인데 커서가 안 바뀌면 종료(무한루프 방지)
            if (!page.hasNext() || next == null || next.equals(cursor)) break;
            cursor = next;
            sleep();
        }
        return new Collected(pages, collected);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rawPage(String json) {
        try {
            return om.readValue(json, Map.class);
        } catch (JacksonException e) {
            throw new ApifyException("댓글 페이지 파싱 실패: " + e.getMessage(), e);
        }
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

    @Override
    public RawSource rawSource() {
        return RawSource.SELF_GQL;
    }
}
