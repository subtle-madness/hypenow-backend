package com.celfit.crawler.dashboard.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class JobCostEstimatorTest {

    static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final SearchKeywordRepository searchKeywords = mock(SearchKeywordRepository.class);
    private final InfluencerRepository influencers = mock(InfluencerRepository.class);
    private final DiscoverSourceSetting discoverSource = mock(DiscoverSourceSetting.class);
    private final ProfileSourceSetting profileSource = mock(ProfileSourceSetting.class);
    private final ProfileSupplementSetting profileSupplement = mock(ProfileSupplementSetting.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final HikerProperties hikerProperties = new HikerProperties("key", "http://x", null, 0.001);
    private final com.celfit.crawler.crawling.adapter.out.datalikers.DataLikersProperties dataLikersProperties =
            new com.celfit.crawler.crawling.adapter.out.datalikers.DataLikersProperties("key", "http://x", null, 0.0006);

    private final JobCostEstimator estimator = new JobCostEstimator(
            searchKeywords, influencers, discoverSource, profileSource, profileSupplement, settings,
            hikerProperties, dataLikersProperties, CLOCK);

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
        when(influencers.countByStatusAndFollowersIsNull(InfluencerStatus.DISCOVERED)).thenReturn(10L);
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
        when(influencers.countByStatusAndFollowersIsNull(InfluencerStatus.DISCOVERED)).thenReturn(50L);
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
    void qualify_DATALIKERS_프로필이면_계정당_1회를_DataLikers_단가로_계산한다() {
        when(searchKeywords.findByEnabledTrue()).thenReturn(List.of());
        when(discoverSource.current()).thenReturn(DiscoverSource.HIKER);
        when(settings.resultsLimit()).thenReturn(0);
        when(settings.qualifyBatchLimit()).thenReturn(100);
        when(settings.collectBatchLimit()).thenReturn(0);
        when(influencers.countByStatusAndFollowersIsNull(InfluencerStatus.DISCOVERED)).thenReturn(50L);
        when(profileSource.current()).thenReturn(ProfileSource.DATALIKERS);
        when(profileSupplement.relatedEnabled()).thenReturn(false);

        JobCost qualify = byJob(estimator.estimates()).get("qualify");

        // 50계정 × 1요청 × $0.0006 = $0.03 (HikerAPI $0.001이 아니라 DataLikers 단가)
        assertThat(qualify.targets()).isEqualTo(50);
        assertThat(qualify.minRequests()).isEqualTo(50);
        assertThat(qualify.minCostUsd()).isEqualTo(0.03);
        assertThat(qualify.endpoints()).anySatisfy(e -> assertThat(e).contains("DataLikers"));
    }

    @Test
    void collect_DATALIKERS_프로필이면_프로필은_DataLikers_피드릴스는_Hiker_단가로_합산한다() {
        when(searchKeywords.findByEnabledTrue()).thenReturn(List.of());
        when(discoverSource.current()).thenReturn(DiscoverSource.HIKER);
        when(settings.resultsLimit()).thenReturn(0);
        when(settings.qualifyBatchLimit()).thenReturn(0);
        when(settings.collectBatchLimit()).thenReturn(10);
        when(influencers.countByStatusAndFollowersIsNull(InfluencerStatus.DISCOVERED)).thenReturn(0L);
        when(influencers.countBackfillPending()).thenReturn(2L);
        when(influencers.countTrackDue(any())).thenReturn(0L);
        when(profileSource.current()).thenReturn(ProfileSource.DATALIKERS);
        when(profileSupplement.relatedEnabled()).thenReturn(false);

        JobCost collect = byJob(estimator.estimates()).get("collect");

        // 계정당: 프로필 1(DataLikers) + 피드폴백 1 + 릴스 1(Hiker) = 3요청 → 2계정 = 6요청
        // 비용: 2 × (1×$0.0006 + 2×$0.001) = 2 × $0.0026 = $0.0052
        assertThat(collect.minRequests()).isEqualTo(6);
        assertThat(collect.minCostUsd()).isEqualTo(0.0052);
        assertThat(collect.endpoints()).anySatisfy(e -> assertThat(e).contains("DataLikers"));
        assertThat(collect.endpoints()).anySatisfy(e -> assertThat(e).contains("gql/user/medias"));
    }

    @Test
    void collect_SELF_프로필이면_피드는_프로필_내장이라_계정당_릴스_1회만_과금된다() {
        when(searchKeywords.findByEnabledTrue()).thenReturn(List.of());
        when(discoverSource.current()).thenReturn(DiscoverSource.HIKER);
        when(settings.resultsLimit()).thenReturn(0);
        when(settings.qualifyBatchLimit()).thenReturn(0);
        when(settings.collectBatchLimit()).thenReturn(5);
        when(influencers.countByStatusAndFollowersIsNull(InfluencerStatus.DISCOVERED)).thenReturn(0L);
        when(influencers.countBackfillPending()).thenReturn(2L);
        when(influencers.countTrackDue(any())).thenReturn(1L);
        when(profileSource.current()).thenReturn(ProfileSource.SELF);
        when(profileSupplement.relatedEnabled()).thenReturn(false);

        JobCost collect = byJob(estimator.estimates()).get("collect");

        // targets = min(5, 3) = 3, 계정당 0(프로필, 피드 12개 내장) + 1(릴스) = 1회
        assertThat(collect.targets()).isEqualTo(3);
        assertThat(collect.minRequests()).isEqualTo(3);
        assertThat(collect.maxRequests()).isEqualTo(3);
        assertThat(collect.minCostUsd()).isEqualTo(0.003);
        assertThat(collect.maxCostUsd()).isEqualTo(0.003);
        assertThat(collect.endpoints()).anySatisfy(e -> assertThat(e).contains("내장"));
        assertThat(collect.endpoints()).anySatisfy(e -> assertThat(e).contains("user/clips"));
        assertThat(collect.endpoints()).noneSatisfy(e -> assertThat(e).contains("gql/user/medias"));
    }

    @Test
    void collect_HIKER_WEB_GQL_프로필이면_프로필과_피드_폴백이_계정당_1회씩_추가된다() {
        when(searchKeywords.findByEnabledTrue()).thenReturn(List.of());
        when(discoverSource.current()).thenReturn(DiscoverSource.HIKER);
        when(settings.resultsLimit()).thenReturn(0);
        when(settings.qualifyBatchLimit()).thenReturn(0);
        when(settings.collectBatchLimit()).thenReturn(10);
        when(influencers.countByStatusAndFollowersIsNull(InfluencerStatus.DISCOVERED)).thenReturn(0L);
        when(influencers.countBackfillPending()).thenReturn(2L);
        when(influencers.countTrackDue(any())).thenReturn(0L);
        when(profileSource.current()).thenReturn(ProfileSource.HIKER_WEB_GQL);
        when(profileSupplement.relatedEnabled()).thenReturn(false);

        JobCost collect = byJob(estimator.estimates()).get("collect");

        // 계정당 1(프로필) + 1(피드 폴백) + 1(릴스) = 3회 → 2계정 = 6회
        assertThat(collect.minRequests()).isEqualTo(6);
        assertThat(collect.maxRequests()).isEqualTo(6);
        assertThat(collect.endpoints()).anySatisfy(e -> assertThat(e).contains("gql/user/medias"));
    }
}
