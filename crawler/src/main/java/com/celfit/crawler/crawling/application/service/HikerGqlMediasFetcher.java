package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.UserMediaPageFetcher;
import com.celfit.crawler.crawling.domain.RawSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 피드 게시물 열거 — HikerAPI /gql/user/medias (flat). 응답 원형을 그대로 반환. */
@Component
public class HikerGqlMediasFetcher implements UserMediaPageFetcher {

    private final HikerHttp http;
    private final ObjectMapper om;

    public HikerGqlMediasFetcher(HikerHttp http, ObjectMapper om) {
        this.http = http;
        this.om = om;
    }

    @Override
    public RawSource source() {
        return RawSource.HIKER_GQL_MEDIAS;
    }

    @Override
    public Map<String, Object> fetchPage(String userId, String cursor) {
        String path = "/gql/user/medias?user_id=" + userId + "&flat=true";
        if (cursor != null) {
            path += "&profile_grid_items_cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
        }
        return parse(http.get(path));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        try {
            return om.readValue(json, Map.class);
        } catch (JacksonException e) {
            throw new ApifyException("medias 페이지 파싱 실패: " + e.getMessage(), e);
        }
    }
}
