package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.adapter.out.datalikers.DataLikersHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DataLikersProfileFetcherTest {

    static CrawlExecutor passthrough() { return SelfProfileFetcherTest.passthroughExecutor(); }
    tools.jackson.databind.ObjectMapper om = new tools.jackson.databind.ObjectMapper();

    // DataLikers /v1/user/by/username 실제 응답 형태 — flat 유저 객체(follower_count·pk 최상위).
    static final String FLAT_USER = """
        {"username":"tem.duck","pk":"74756186520","follower_count":256559}""";

    @Test void username별_조회_응답_원형을_그대로_반환하고_flat_필드를_추출한다() {
        DataLikersHttp http = path -> {
            assertThat(path).contains("/v1/user/by/username?username=tem.duck");
            return FLAT_USER;
        };
        var f = new DataLikersProfileFetcher(http, passthrough(), om);
        assertThat(f.source()).isEqualTo(ProfileSource.DATALIKERS);
        assertThat(f.rawSource()).isEqualTo(RawSource.DATALIKERS);

        var ex = f.fetch(JobName.QUALIFY, List.of("tem.duck"), TriggerType.MANUAL);
        Map<String, Object> item = ex.items().get(0);
        assertThat(ProfileExtractor.username(item, RawSource.DATALIKERS)).isEqualTo("tem.duck");
        assertThat(ProfileExtractor.followers(item, RawSource.DATALIKERS)).isEqualTo(256559L);
        assertThat(ProfileExtractor.userId(item, RawSource.DATALIKERS)).isEqualTo("74756186520");
    }

    @Test void 한_계정_실패해도_나머지_청크는_계속한다() {
        AtomicInteger calls = new AtomicInteger();
        DataLikersHttp http = path -> {
            calls.incrementAndGet();
            if (path.contains("username=bad.user")) throw new ApifyException("DataLikers HTTP 500");
            return FLAT_USER;
        };
        var f = new DataLikersProfileFetcher(http, passthrough(), om);
        var ex = f.fetch(JobName.QUALIFY, List.of("bad.user", "tem.duck"), TriggerType.MANUAL);
        assertThat(calls.get()).isEqualTo(2);   // 첫 계정 실패해도 두번째 호출까지 진행
        assertThat(ex.items()).hasSize(1);
        assertThat(ProfileExtractor.username(ex.items().get(0), RawSource.DATALIKERS)).isEqualTo("tem.duck");
    }
}
