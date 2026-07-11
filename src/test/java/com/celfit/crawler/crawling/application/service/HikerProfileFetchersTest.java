package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HikerProfileFetchersTest {

    static CrawlExecutor passthrough() { return SelfProfileFetcherTest.passthroughExecutor(); }
    ProfileMapper mapper = new ProfileMapper(new tools.jackson.databind.ObjectMapper());

    @Test void mobile_username별_조회_정규화() {
        HikerHttp http = path -> {
            assertThat(path).contains("/v2/user/by/username");
            return """
                {"user":{"username":"tem.duck","pk":"74756186520","follower_count":256559}}""";
        };
        var f = new HikerMobileProfileFetcher(http, passthrough(), mapper);
        assertThat(f.source()).isEqualTo(ProfileSource.HIKER_MOBILE);
        var ex = f.fetch(List.of("tem.duck"), TriggerType.MANUAL);
        assertThat(ex.items().get(0).get("followersCount")).isEqualTo(256559L);
        assertThat(ex.items().get(0).get("userId")).isEqualTo("74756186520");
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
        var f = new HikerMobileProfileFetcher(http, passthrough(), mapper);
        var ex = f.fetch(List.of("bad.user", "tem.duck"), TriggerType.MANUAL);
        assertThat(calls.get()).isEqualTo(2);  // 첫 계정 실패해도 두번째 계정 호출까지 진행
        assertThat(ex.items()).hasSize(1);
        assertThat(ex.items().get(0).get("username")).isEqualTo("tem.duck");
    }

    @Test void webgql_500이면_해당_계정_스킵() {
        HikerHttp http = path -> {
            if (path.contains("/v2/user/by/username")) {
                return """
                    {"user":{"username":"tem.duck","pk":"74756186520","follower_count":256559}}""";
            }
            throw new com.celfit.crawler.crawling.application.port.out.ApifyException("Hiker HTTP 500");
        };
        var f = new HikerWebGqlProfileFetcher(http, passthrough(), mapper);
        assertThat(f.source()).isEqualTo(ProfileSource.HIKER_WEB_GQL);
        var ex = f.fetch(List.of("tem.duck"), TriggerType.MANUAL);
        assertThat(ex.items()).isEmpty();  // 500 → 스킵, 예외 전파 안 함
    }
}
