package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import tools.jackson.databind.ObjectMapper;

class HikerWebGqlProfileFetcherTest {

    HikerHttp http = mock(HikerHttp.class);
    CrawlExecutor executor = mock(CrawlExecutor.class);
    InfluencerRepository influencers = mock(InfluencerRepository.class);
    ObjectMapper om = new ObjectMapper();

    HikerWebGqlProfileFetcher fetcher = new HikerWebGqlProfileFetcher(http, executor, influencers, om);

    @SuppressWarnings("unchecked")
    @BeforeEach
    void wireExecutorPassthrough() {
        when(executor.execute(any(), any(), any(), any(), any(), any(Supplier.class)))
                .thenAnswer(inv -> {
                    Supplier<ApifyResult> work = inv.getArgument(5);
                    return new CrawlExecutor.Execution(1L, work.get().items());
                });
    }

    static String gqlUserJson(String username) {
        return "{\"user\":{\"username\":\"" + username + "\",\"follower_count\":5000,\"pk\":\"PK1\"}}";
    }

    @Test
    void 저장된_pk가_있으면_by_username_해석을_생략하고_gql_1요청만_한다() {
        Influencer inf = new Influencer("alice");
        inf.setIgUserId("PK1");
        when(influencers.findByUsername("alice")).thenReturn(Optional.of(inf));
        when(http.get("/gql/user/web_profile_info?user_id=PK1")).thenReturn(gqlUserJson("alice"));

        var ex = fetcher.fetch(JobName.QUALIFY, List.of("alice"), TriggerType.MANUAL);

        assertThat(ex.items()).hasSize(1);
        verify(http, never()).get(ArgumentMatchers.contains("/v2/user/by/username"));
    }

    @Test
    void pk가_없으면_기존대로_by_username_해석_후_gql을_요청한다() {
        when(influencers.findByUsername("bob")).thenReturn(Optional.of(new Influencer("bob")));
        when(http.get("/v2/user/by/username?username=bob"))
                .thenReturn("{\"username\":\"bob\",\"pk\":\"PK2\"}");
        when(http.get("/gql/user/web_profile_info?user_id=PK2")).thenReturn(gqlUserJson("bob"));

        var ex = fetcher.fetch(JobName.QUALIFY, List.of("bob"), TriggerType.MANUAL);

        assertThat(ex.items()).hasSize(1);
        verify(http).get("/v2/user/by/username?username=bob");
    }
}
