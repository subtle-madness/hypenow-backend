package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HikerProfileFetchersTest {

    static CrawlExecutor passthrough() { return SelfProfileFetcherTest.passthroughExecutor(); }
    tools.jackson.databind.ObjectMapper om = new tools.jackson.databind.ObjectMapper();

    @Test void mobile_username별_조회_응답_원형을_그대로_반환() {
        HikerHttp http = path -> {
            assertThat(path).contains("/v2/user/by/username");
            return """
                {"user":{"username":"tem.duck","pk":"74756186520","follower_count":256559}}""";
        };
        var f = new HikerMobileProfileFetcher(http, passthrough(), om);
        assertThat(f.source()).isEqualTo(ProfileSource.HIKER_MOBILE);
        assertThat(f.rawSource()).isEqualTo(RawSource.HIKER_MOBILE);
        var ex = f.fetch(List.of("tem.duck"), TriggerType.MANUAL);
        Map<String, Object> item = ex.items().get(0);
        assertThat(ProfileExtractor.followers(item, RawSource.HIKER_MOBILE)).isEqualTo(256559L);
        assertThat(ProfileExtractor.userId(item, RawSource.HIKER_MOBILE)).isEqualTo("74756186520");
        assertThat(item).containsKey("user"); // 원형 그대로 보존
    }

    @Test void mobile_한_계정_실패해도_나머지_청크는_성공() {
        AtomicInteger calls = new AtomicInteger();
        HikerHttp http = path -> {
            calls.incrementAndGet();
            if (path.contains("username=bad.user")) {
                throw new ApifyException("Hiker HTTP 500");
            }
            return """
                {"user":{"username":"tem.duck","pk":"74756186520","follower_count":256559}}""";
        };
        var f = new HikerMobileProfileFetcher(http, passthrough(), om);
        var ex = f.fetch(List.of("bad.user", "tem.duck"), TriggerType.MANUAL);
        assertThat(calls.get()).isEqualTo(2);  // 첫 계정 실패해도 두번째 계정 호출까지 진행
        assertThat(ex.items()).hasSize(1);
        assertThat(ProfileExtractor.username(ex.items().get(0), RawSource.HIKER_MOBILE)).isEqualTo("tem.duck");
    }

    @Test void webgql_500이면_해당_계정_스킵() {
        HikerHttp http = path -> {
            if (path.contains("/v2/user/by/username")) {
                return """
                    {"user":{"username":"tem.duck","pk":"74756186520","follower_count":256559}}""";
            }
            throw new ApifyException("Hiker HTTP 500");
        };
        var f = new HikerWebGqlProfileFetcher(http, passthrough(), om);
        assertThat(f.source()).isEqualTo(ProfileSource.HIKER_WEB_GQL);
        assertThat(f.rawSource()).isEqualTo(RawSource.HIKER_MOBILE);
        var ex = f.fetch(List.of("tem.duck"), TriggerType.MANUAL);
        assertThat(ex.items()).isEmpty();  // 500 → 스킵, 예외 전파 안 함
    }
}
