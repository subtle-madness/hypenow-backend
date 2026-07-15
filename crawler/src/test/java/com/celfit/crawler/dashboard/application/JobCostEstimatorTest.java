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
    void collect_DATALIKERS_프로필이면_프로필은_DataLikers_피드폴백은_Hiker_단가로_합산한다() {
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

        // 릴스·피드폴백 모두 제거 — 계정당 프로필 1요청(DataLikers)만 → 2계정 = 2요청
        // 비용: 2 × $0.0006 = $0.0012 (부동소수점 오차 허용)
        assertThat(collect.minRequests()).isEqualTo(2);
        assertThat(collect.minCostUsd()).isCloseTo(0.0012, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(collect.endpoints()).anySatisfy(e -> assertThat(e).contains("DataLikers"));
        assertThat(collect.endpoints()).noneSatisfy(e -> assertThat(e).contains("gql/user/medias"));
        assertThat(collect.endpoints()).noneSatisfy(e -> assertThat(e).contains("clips"));
    }

    @Test
    void collect_SELF_프로필이면_피드_내장이라_유료_요청이_없다() {
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

        // targets = min(5, 3) = 3, 릴스 분리 후 SELF는 계정당 0요청(프로필 무료, 피드 12개 내장)
        assertThat(collect.targets()).isEqualTo(3);
        assertThat(collect.minRequests()).isZero();
        assertThat(collect.maxRequests()).isZero();
        assertThat(collect.minCostUsd()).isZero();
        assertThat(collect.maxCostUsd()).isZero();
        assertThat(collect.endpoints()).anySatisfy(e -> assertThat(e).contains("내장"));
        assertThat(collect.endpoints()).noneSatisfy(e -> assertThat(e).contains("user/clips"));
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

        // 피드 폴백 제거 — 계정당 프로필 1회만 → 2계정 = 2회
        assertThat(collect.minRequests()).isEqualTo(2);
        assertThat(collect.maxRequests()).isEqualTo(2);
        assertThat(collect.endpoints()).noneSatisfy(e -> assertThat(e).contains("gql/user/medias"));
    }

    @Test
    void reels는_대기_계정을_배치_한도로_자르고_계정당_Hiker_1요청으로_추정한다() {
        when(searchKeywords.findByEnabledTrue()).thenReturn(List.of());
        when(discoverSource.current()).thenReturn(DiscoverSource.HIKER);
        when(settings.resultsLimit()).thenReturn(0);
        when(settings.qualifyBatchLimit()).thenReturn(0);
        when(settings.collectBatchLimit()).thenReturn(0);
        when(settings.reelsBatchLimit()).thenReturn(10);
        when(influencers.countByStatusAndFollowersIsNull(InfluencerStatus.DISCOVERED)).thenReturn(0L);
        when(influencers.countBackfillPending()).thenReturn(0L);
        when(influencers.countTrackDue(any())).thenReturn(0L);
        when(influencers.countReelsDue(any())).thenReturn(25L);
        when(profileSource.current()).thenReturn(ProfileSource.SELF);
        when(profileSupplement.relatedEnabled()).thenReturn(false);

        JobCost reels = byJob(estimator.estimates()).get("reels");

        // targets = min(10, 25), 계정당 /v2/user/clips 1요청 → 10 × $0.001
        assertThat(reels.targets()).isEqualTo(10);
        assertThat(reels.minRequests()).isEqualTo(10);
        assertThat(reels.maxRequests()).isEqualTo(10);
        assertThat(reels.minCostUsd()).isEqualTo(0.010);
        assertThat(reels.endpoints()).anySatisfy(e -> assertThat(e).contains("clips"));
    }

    @Test
    void beauty는_유료_요청_0건_비용_0달러() {
        when(searchKeywords.findByEnabledTrue()).thenReturn(List.of());
        when(discoverSource.current()).thenReturn(DiscoverSource.HIKER);
        when(settings.resultsLimit()).thenReturn(0);
        when(settings.qualifyBatchLimit()).thenReturn(0);
        when(settings.collectBatchLimit()).thenReturn(0);
        when(settings.revisitIntervalDays()).thenReturn(0);
        when(influencers.countByStatusAndFollowersIsNull(InfluencerStatus.DISCOVERED)).thenReturn(0L);
        when(influencers.countBackfillPending()).thenReturn(0L);
        when(influencers.countTrackDue(any())).thenReturn(0L);
        when(influencers.countByStatusAndBeautyIsNull(InfluencerStatus.QUALIFIED)).thenReturn(516L);
        when(settings.beautyBatchLimit()).thenReturn(500);
        when(profileSource.current()).thenReturn(ProfileSource.SELF);
        when(profileSupplement.relatedEnabled()).thenReturn(false);

        var beauty = estimator.estimates().stream()
                .filter(c -> c.job().equals("beauty")).findFirst().orElseThrow();

        // 대기 516명이라도 실행 1회는 배치 한도(500)까지만
        assertThat(beauty.targets()).isEqualTo(500);
        assertThat(beauty.maxRequests()).isZero();
        assertThat(beauty.maxCostUsd()).isZero();
    }

    @Test
    void similar는_배치_상한만큼_시드당_1회_pk_미보유만_최대_2회로_추정한다() {
        when(searchKeywords.findByEnabledTrue()).thenReturn(List.of());
        when(discoverSource.current()).thenReturn(DiscoverSource.HIKER);
        when(settings.resultsLimit()).thenReturn(0);
        when(settings.qualifyBatchLimit()).thenReturn(0);
        when(settings.collectBatchLimit()).thenReturn(0);
        when(settings.revisitIntervalDays()).thenReturn(0);
        when(influencers.countByStatusAndFollowersIsNull(InfluencerStatus.DISCOVERED)).thenReturn(0L);
        when(influencers.countBackfillPending()).thenReturn(0L);
        when(influencers.countTrackDue(any())).thenReturn(0L);
        when(settings.similarBatchLimit()).thenReturn(50);
        when(influencers.countByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                InfluencerStatus.QUALIFIED)).thenReturn(200L);
        when(influencers.countByStatusAndBeautyTrueAndSimilarProcessedAtIsNullAndIgUserIdIsNull(
                InfluencerStatus.QUALIFIED)).thenReturn(30L);
        when(profileSource.current()).thenReturn(ProfileSource.SELF);
        when(profileSupplement.relatedEnabled()).thenReturn(false);

        var similar = estimator.estimates().stream()
                .filter(c -> c.job().equals("similar")).findFirst().orElseThrow();

        assertThat(similar.targets()).isEqualTo(50);   // min(배치 50, 대기 200)
        assertThat(similar.minRequests()).isEqualTo(50);   // 전원 pk 보유 가정
        assertThat(similar.maxRequests()).isEqualTo(80);   // pk 미보유 30명이 배치에 다 들면 +30
        assertThat(similar.minCostUsd()).isEqualTo(0.050);
        assertThat(similar.maxCostUsd()).isEqualTo(0.080);
    }
}
