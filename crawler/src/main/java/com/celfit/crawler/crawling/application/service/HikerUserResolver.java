package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * username → 인스타 내부 pk 해석 (HikerAPI /v1/user/by/username, 유료 1요청).
 * SIMILAR 잡이 ig_user_id 미보유 시드(대부분 레거시 이관분)에 폴백으로 쓴다.
 */
@Component
public class HikerUserResolver {

    private final HikerHttp http;
    private final ObjectMapper om;

    public HikerUserResolver(HikerHttp http, ObjectMapper om) {
        this.http = http;
        this.om = om;
    }

    /** 응답에 pk(폴백 id)가 없으면 null. 전송 오류는 ApifyException 전파. */
    public String resolvePk(String username) {
        String body = http.get("/v1/user/by/username?username="
                + URLEncoder.encode(username, StandardCharsets.UTF_8));
        try {
            JsonNode root = om.readTree(body);
            JsonNode user = root.path("user").isObject() ? root.path("user") : root;
            String pk = user.path("pk").asString("");
            if (pk.isBlank()) pk = user.path("id").asString("");
            return pk.isBlank() ? null : pk;
        } catch (JacksonException e) {
            throw new ApifyException("user by username 파싱 실패: " + e.getMessage(), e);
        }
    }
}
