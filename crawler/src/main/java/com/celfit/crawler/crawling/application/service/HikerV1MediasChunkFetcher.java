package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.UserMediaPageFetcher;
import com.celfit.crawler.crawling.domain.RawSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 피드 게시물 열거 — HikerAPI /v1/user/medias/chunk (모바일 API 계열).
 * gql 계열(/gql/user/medias·web_profile_info)이 IG 웹 업스트림 장애로 죽어도 살아있는 경로
 * (2026-07-18 실측: gql 전면 500, /v1·/v2 모바일 계열 정상).
 * 응답이 [medias 배열, end_cursor] 최상위 배열이라 Map 원형으로 감싸 저장한다.
 */
@Component
public class HikerV1MediasChunkFetcher implements UserMediaPageFetcher {

    private final HikerHttp http;
    private final ObjectMapper om;

    public HikerV1MediasChunkFetcher(HikerHttp http, ObjectMapper om) {
        this.http = http;
        this.om = om;
    }

    @Override
    public RawSource source() {
        return RawSource.HIKER_V1_MEDIAS;
    }

    @Override
    public Map<String, Object> fetchPage(String userId, String cursor) {
        String path = "/v1/user/medias/chunk?user_id=" + userId;
        if (cursor != null) {
            path += "&end_cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
        }
        return wrap(http.get(path));
    }

    /** [[Media...], end_cursor] → {"medias": [...], "next_end_cursor": ...} — jsonb 저장은 객체 원형으로. */
    private Map<String, Object> wrap(String json) {
        try {
            List<?> arr = om.readValue(json, List.class);
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("medias", !arr.isEmpty() && arr.get(0) instanceof List<?> l ? l : List.of());
            page.put("next_end_cursor", arr.size() > 1 ? arr.get(1) : null);
            return page;
        } catch (JacksonException e) {
            throw new ApifyException("medias chunk 파싱 실패: " + e.getMessage(), e);
        }
    }
}
