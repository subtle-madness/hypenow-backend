package com.celfit.crawler.dashboard.application;

import com.celfit.crawler.content.application.port.out.SearchKeywordRepository;
import com.celfit.crawler.crawling.adapter.out.hiker.HikerProperties;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.settings.application.service.DiscoverSourceSetting;
import com.celfit.crawler.settings.application.service.ProfileSourceSetting;
import com.celfit.crawler.settings.application.service.ProfileSupplementSetting;
import com.celfit.crawler.settings.application.service.SettingsService;
import com.celfit.crawler.settings.domain.DiscoverSource;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 잡(discover/qualify/collect) 실행 예상 비용·사용 엔드포인트 추정 — /ui/jobs 표시용.
 * HikerAPI는 요청 1회당 {@link HikerProperties#costPerRequestUsd()} 과금. 실제 요청 수는
 * 계정·게시물 규모에 따라 달라지므로 min~max 범위로만 추정한다(정산은 실제 request_count 기준).
 */
@Service
public class JobCostEstimator {

    /** discover 페이지당 게시물 수 실측 하한 — 페이지 요청 수 추정용. */
    private static final double DISCOVER_ITEMS_PER_PAGE = 25.0;

    public record JobCost(String job, List<String> endpoints, long targets,
                          long minRequests, long maxRequests,
                          double minCostUsd, double maxCostUsd, String note) {}

    private final SearchKeywordRepository searchKeywords;
    private final InfluencerRepository influencers;
    private final DiscoverSourceSetting discoverSource;
    private final ProfileSourceSetting profileSource;
    private final ProfileSupplementSetting profileSupplement;
    private final SettingsService settings;
    private final HikerProperties hikerProperties;
    private final Clock clock;

    public JobCostEstimator(SearchKeywordRepository searchKeywords, InfluencerRepository influencers,
                            DiscoverSourceSetting discoverSource, ProfileSourceSetting profileSource,
                            ProfileSupplementSetting profileSupplement, SettingsService settings,
                            HikerProperties hikerProperties, Clock clock) {
        this.searchKeywords = searchKeywords;
        this.influencers = influencers;
        this.discoverSource = discoverSource;
        this.profileSource = profileSource;
        this.profileSupplement = profileSupplement;
        this.settings = settings;
        this.hikerProperties = hikerProperties;
        this.clock = clock;
    }

    public List<JobCost> estimates() {
        return List.of(discoverEstimate(), qualifyEstimate(), collectEstimate());
    }

    private JobCost discoverEstimate() {
        long targets = searchKeywords.findByEnabledTrue().size();
        if (discoverSource.current() == DiscoverSource.ACTOR) {
            return new JobCost("discover", List.of("Apify hashtag actor"), targets,
                    0, 0, 0, 0, "Apify 액터 과금 별도(~$0.0023/게시물)");
        }
        long perKeyword = (long) Math.ceil(settings.resultsLimit() / DISCOVER_ITEMS_PER_PAGE);
        long requests = targets * perKeyword;
        double cost = requests * hikerProperties.costPerRequestUsd();
        return new JobCost("discover",
                List.of("HikerAPI /v2/hashtag/medias/top (키워드당 페이지 반복)"),
                targets, requests, requests, cost, cost, "페이지당 25~30건 실측 기준 추정");
    }

    private JobCost qualifyEstimate() {
        long targets = Math.min((long) settings.qualifyBatchLimit(),
                influencers.countByStatusAndLastProfiledAtIsNull(InfluencerStatus.DISCOVERED));
        List<String> endpoints = new ArrayList<>();
        long perAccount = profileRequestsPerAccount(endpoints);
        String note = profileSource.current() == ProfileSource.ACTOR ? "Apify 액터 과금 별도" : null;
        long requests = targets * perAccount;
        double cost = requests * hikerProperties.costPerRequestUsd();
        return new JobCost("qualify", endpoints, targets, requests, requests, cost, cost, note);
    }

    private JobCost collectEstimate() {
        Instant revisitBefore = clock.instant().minus(Duration.ofDays(settings.revisitIntervalDays()));
        long collectDue = influencers.countBackfillPending() + influencers.countTrackDue(revisitBefore);
        long targets = Math.min((long) settings.collectBatchLimit(), collectDue);
        List<String> endpoints = new ArrayList<>();
        long perAccount = profileRequestsPerAccount(endpoints);
        // 피드: SELF 프로필 원형에 최근 12개가 내장 — 다른 소스는 피드 1페이지 폴백(유료 1요청)
        if (profileSource.current() == ProfileSource.SELF) {
            endpoints.add("최근 피드 12개 — 프로필 원형에 내장 (추가 요청 없음)");
        } else {
            endpoints.add("HikerAPI /gql/user/medias (계정당 1회 — 피드 폴백)");
            perAccount += 1;
        }
        endpoints.add("HikerAPI /v2/user/clips (계정당 1회 — 릴스)");
        perAccount += 1;
        endpoints.add("instagram GraphQL 댓글 (self, 무료)");
        long requests = targets * perAccount;
        double cost = requests * hikerProperties.costPerRequestUsd();
        return new JobCost("collect", endpoints, targets, requests, requests, cost, cost,
                "방문당 최근 피드 12개 + 릴스 1페이지 — 기간 백필·페이지네이션 없음");
    }

    /**
     * 현재 프로필 소스 기준 계정당 요청 수를 endpoints에 채우고 반환한다.
     * related 보충(profile.supplement.related)이 켜져 있으면 소스와 무관하게 +1.
     */
    private long profileRequestsPerAccount(List<String> endpoints) {
        ProfileSource source = profileSource.current();
        long per = switch (source) {
            case SELF -> {
                endpoints.add("instagram web_profile_info (self, 무료)");
                yield 0;
            }
            case HIKER_MOBILE, HIKER_WEB_GQL -> {
                endpoints.add("HikerAPI /v2/user/by/username (계정당 1회)");
                yield 1;
            }
            case ACTOR -> {
                endpoints.add("Apify 프로필 액터");
                yield 0;
            }
        };
        if (profileSupplement.relatedEnabled()) {
            endpoints.add("HikerAPI /v2/user/suggested/profiles (+계정당 1회)");
            per += 1;
        }
        return per;
    }
}
