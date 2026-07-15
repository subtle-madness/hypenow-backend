package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.adapter.out.datalikers.DataLikersHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * DataLikers base — username별 /v1/user/by/username 단건 조회(응답 원형 그대로 반환). 자체 인프라라
 * 프록시(429·TLS 끊김)를 타지 않는다. 응답은 flat 유저 객체(follower_count·pk 최상위)로 HIKER_MOBILE과
 * 동일 구조라 ProfileExtractor DATALIKERS 경로가 그대로 추출한다. 프로필엔 최근 게시물이 안 딸려오므로
 * collect 방문 시 피드는 폴백(HikerAPI clips/medias)으로 채워진다.
 */
@Component
public class DataLikersProfileFetcher implements ProfileFetcher {

    static final String LABEL = "profile-datalikers";
    private static final Logger log = LoggerFactory.getLogger(DataLikersProfileFetcher.class);

    private final DataLikersHttp http;
    private final CrawlExecutor executor;
    private final ObjectMapper om;

    public DataLikersProfileFetcher(DataLikersHttp http, CrawlExecutor executor, ObjectMapper om) {
        this.http = http;
        this.executor = executor;
        this.om = om;
    }

    @Override
    public CrawlExecutor.Execution fetch(JobName job, List<String> usernames, TriggerType trigger) {
        return executor.execute(job, trigger, null, null, LABEL,
                () -> new ApifyResult(null, collect(usernames)));
    }

    private List<Map<String, Object>> collect(List<String> usernames) {
        List<Map<String, Object>> out = new ArrayList<>();
        int total = usernames.size(), i = 0;
        for (String u : usernames) {
            i++;
            try {
                String enc = URLEncoder.encode(u, StandardCharsets.UTF_8);
                Map<String, Object> p = readRoot(http.get("/v1/user/by/username?username=" + enc));
                if (ProfileExtractor.username(p, RawSource.DATALIKERS) != null) {
                    out.add(p);
                    log.info("프로필 ({}/{}) {} — 확보(DataLikers)", i, total, u);
                } else {
                    log.info("프로필 ({}/{}) {} — 스킵(응답에 계정 없음)", i, total, u);
                }
            } catch (ApifyException e) {
                log.warn("프로필 ({}/{}) {} — 요청 실패, 해당 계정만 건너뜀 ({})", i, total, u, e.getMessage());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readRoot(String json) {
        try {
            return om.readValue(json, Map.class);
        } catch (JacksonException e) {
            throw new ApifyException("프로필 JSON 파싱 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public ProfileSource source() {
        return ProfileSource.DATALIKERS;
    }

    @Override
    public RawSource rawSource() {
        return RawSource.DATALIKERS;
    }
}
