package com.celfit.crawler.dashboard.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.crawler.content.application.port.out.SearchKeywordRepository;
import com.celfit.crawler.crawling.adapter.out.hiker.HikerProperties;
import com.celfit.crawler.content.domain.SearchKeyword;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.dashboard.application.JobCostEstimator.JobCost;
import com.celfit.crawler.settings.application.service.DiscoverSourceSetting;
import com.celfit.crawler.settings.application.service.ProfileSourceSetting;
import com.celfit.crawler.settings.application.service.ProfileSupplementSetting;
import com.celfit.crawler.settings.application.service.SettingsService;
import com.celfit.crawler.settings.domain.DiscoverSource;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class JobCostEstimatorTest {

    private final SearchKeywordRepository searchKeywords = mock(SearchKeywordRepository.class);
    private final InfluencerRepository influencers = mock(InfluencerRepository.class);
    private final DiscoverSourceSetting discoverSource = mock(DiscoverSourceSetting.class);
    private final ProfileSourceSetting profileSource = mock(ProfileSourceSetting.class);
    private final ProfileSupplementSetting profileSupplement = mock(ProfileSupplementSetting.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final HikerProperties hikerProperties = new HikerProperties("key", "http://x", null, 0.001);

    private final JobCostEstimator estimator = new JobCostEstimator(
            searchKeywords, influencers, discoverSource, profileSource, profileSupplement, settings, hikerProperties);

    private static List<SearchKeyword> keywords(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> new SearchKeyword("kw" + i, java.time.Instant.now())).toList();
    }

    private Map<String, JobCost> byJob(List<JobCost> costs) {
        return costs.stream().collect(Collectors.toMap(JobCost::job, c -> c));
    }

    @Test
    void discover_HIKER_소스면_키워드당_페이지_반복으로_요청수를_계산한다() {
        when(searchKeywords.findByEnabledTrue()).thenReturn(keywords(3));
        when(discoverSource.current()).thenReturn(DiscoverSource.HIKER);
        when(settings.resultsLimit()).thenReturn(100);
        when(settings.qualifyBatchLimit()).thenReturn(0);
        when(settings.collectBatchLimit()).thenReturn(0);
        when(profileSource.current()).thenReturn(ProfileSource.SELF);

        JobCost discover = byJob(estimator.estimates()).get("discover");

        assertThat(discover.targets()).isEqualTo(3);
        // ceil(100/25) = 4 요청/키워드 × 3키워드 = 12
        assertThat(discover.minRequests()).isEqualTo(12);
        assertThat(discover.maxRequests()).isEqualTo(12);
        assertThat(discover.minCostUsd()).isEqualTo(0.012);
        assertThat(discover.endpoints()).anySatisfy(e -> assertThat(e).contains("v2/hashtag/medias/top"));
    }

    @Test
    void discover_ACTOR_소스면_비용_0에_Apify_안내_note를_붙인다() {
        when(searchKeywords.findByEnabledTrue()).thenReturn(keywords(3));
        when(discoverSource.current()).thenReturn(DiscoverSource.ACTOR);
        when(settings.qualifyBatchLimit()).thenReturn(0);
        when(settings.collectBatchLimit()).thenReturn(0);
        when(profileSource.current()).thenReturn(ProfileSource.SELF);

        JobCost discover = byJob(estimator.estimates()).get("discover");

        assertThat(discover.minRequests()).isZero();
        assertThat(discover.maxRequests()).isZero();
        assertThat(discover.note()).contains("Apify");
    }

    @Test
    void qualify_HIKER_MOBILE에_related_보충이_켜지면_계정당_2회로_계산하고_targets_상한을_적용한다() {
        when(searchKeywords.findByEnabledTrue()).thenReturn(List.of());
        when(discoverSource.current()).thenReturn(DiscoverSource.HIKER);
        when(settings.resultsLimit()).thenReturn(0);
        when(settings.qualifyBatchLimit()).thenReturn(2);
        when(settings.collectBatchLimit()).thenReturn(0);
        when(influencers.countByStatusAndLastProfiledAtIsNull(InfluencerStatus.DISCOVERED)).thenReturn(10L);
        when(influencers.countByStatus(InfluencerStatus.QUALIFIED)).thenReturn(0L);
        when(profileSource.current()).thenReturn(ProfileSource.HIKER_MOBILE);
        when(profileSupplement.relatedEnabled()).thenReturn(true);

        JobCost qualify = byJob(estimator.estimates()).get("qualify");

        // targets = min(batchLimit=2, discovered=10) = 2
        assertThat(qualify.targets()).isEqualTo(2);
        // 계정당 1(by/username) + 1(related) = 2 → 2*2 = 4
        assertThat(qualify.minRequests()).isEqualTo(4);
        assertThat(qualify.maxRequests()).isEqualTo(4);
        assertThat(qualify.minCostUsd()).isEqualTo(0.004);
        assertThat(qualify.endpoints()).anySatisfy(e -> assertThat(e).contains("by/username"));
        assertThat(qualify.endpoints()).anySatisfy(e -> assertThat(e).contains("suggested/profiles"));
    }

    @Test
    void qualify_SELF_소스면_무료다() {
        when(searchKeywords.findByEnabledTrue()).thenReturn(List.of());
        when(discoverSource.current()).thenReturn(DiscoverSource.HIKER);
        when(settings.resultsLimit()).thenReturn(0);
        when(settings.qualifyBatchLimit()).thenReturn(500);
        when(settings.collectBatchLimit()).thenReturn(0);
        when(influencers.countByStatusAndLastProfiledAtIsNull(InfluencerStatus.DISCOVERED)).thenReturn(50L);
        when(influencers.countByStatus(InfluencerStatus.QUALIFIED)).thenReturn(0L);
        when(profileSource.current()).thenReturn(ProfileSource.SELF);
        when(profileSupplement.relatedEnabled()).thenReturn(false);

        JobCost qualify = byJob(estimator.estimates()).get("qualify");

        assertThat(qualify.targets()).isEqualTo(50);
        assertThat(qualify.minRequests()).isZero();
        assertThat(qualify.maxRequests()).isZero();
        assertThat(qualify.minCostUsd()).isZero();
        assertThat(qualify.endpoints()).anySatisfy(e -> assertThat(e).contains("무료"));
    }

    @Test
    void collect_SELF_프로필이면_계정당_열거페이지_4에서_26회_범위로_계산한다() {
        when(searchKeywords.findByEnabledTrue()).thenReturn(List.of());
        when(discoverSource.current()).thenReturn(DiscoverSource.HIKER);
        when(settings.resultsLimit()).thenReturn(0);
        when(settings.qualifyBatchLimit()).thenReturn(0);
        when(settings.collectBatchLimit()).thenReturn(5);
        when(influencers.countByStatusAndLastProfiledAtIsNull(InfluencerStatus.DISCOVERED)).thenReturn(0L);
        when(influencers.countByStatus(InfluencerStatus.QUALIFIED)).thenReturn(3L);
        when(profileSource.current()).thenReturn(ProfileSource.SELF);
        when(profileSupplement.relatedEnabled()).thenReturn(false);

        JobCost collect = byJob(estimator.estimates()).get("collect");

        // targets = min(5, 3) = 3, 계정당 0(프로필) + 4~26(열거) 페이지
        assertThat(collect.targets()).isEqualTo(3);
        assertThat(collect.minRequests()).isEqualTo(12);
        assertThat(collect.maxRequests()).isEqualTo(78);
        assertThat(collect.minCostUsd()).isEqualTo(0.012);
        assertThat(collect.maxCostUsd()).isEqualTo(0.078);
        assertThat(collect.endpoints()).anySatisfy(e -> assertThat(e).contains("gql/user/medias"));
        assertThat(collect.endpoints()).anySatisfy(e -> assertThat(e).contains("user/clips"));
    }

    @Test
    void collect_HIKER_WEB_GQL_프로필이면_계정당_1회가_추가된다() {
        when(searchKeywords.findByEnabledTrue()).thenReturn(List.of());
        when(discoverSource.current()).thenReturn(DiscoverSource.HIKER);
        when(settings.resultsLimit()).thenReturn(0);
        when(settings.qualifyBatchLimit()).thenReturn(0);
        when(settings.collectBatchLimit()).thenReturn(10);
        when(influencers.countByStatusAndLastProfiledAtIsNull(InfluencerStatus.DISCOVERED)).thenReturn(0L);
        when(influencers.countByStatus(InfluencerStatus.QUALIFIED)).thenReturn(2L);
        when(profileSource.current()).thenReturn(ProfileSource.HIKER_WEB_GQL);
        when(profileSupplement.relatedEnabled()).thenReturn(false);

        JobCost collect = byJob(estimator.estimates()).get("collect");

        // 계정당 1(프로필) + 4~26(열거) → 2*(1+4)=10 ~ 2*(1+26)=54
        assertThat(collect.minRequests()).isEqualTo(10);
        assertThat(collect.maxRequests()).isEqualTo(54);
    }
}
