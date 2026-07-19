package com.celfit.crawler.dashboard.application;

import com.celfit.crawler.common.time.RevisitCutoff;
import com.celfit.crawler.content.application.port.out.SearchKeywordRepository;
import com.celfit.crawler.crawling.adapter.out.datalikers.DataLikersProperties;
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

    /** label — 잡 코드명만으로는 하는 일이 안 보여서 붙이는 한글 설명 (버튼·카드 제목에 노출). */
    public record JobCost(String job, String label, List<String> endpoints, long targets,
                          long minRequests, long maxRequests,
                          double minCostUsd, double maxCostUsd, String note) {}

    private final SearchKeywordRepository searchKeywords;
    private final InfluencerRepository influencers;
    private final DiscoverSourceSetting discoverSource;
    private final ProfileSourceSetting profileSource;
    private final ProfileSupplementSetting profileSupplement;
    private final SettingsService settings;
    private final HikerProperties hikerProperties;
    private final DataLikersProperties dataLikersProperties;
    private final Clock clock;

    public JobCostEstimator(SearchKeywordRepository searchKeywords, InfluencerRepository influencers,
                            DiscoverSourceSetting discoverSource, ProfileSourceSetting profileSource,
                            ProfileSupplementSetting profileSupplement, SettingsService settings,
                            HikerProperties hikerProperties, DataLikersProperties dataLikersProperties,
                            Clock clock) {
        this.searchKeywords = searchKeywords;
        this.influencers = influencers;
        this.discoverSource = discoverSource;
        this.profileSource = profileSource;
        this.profileSupplement = profileSupplement;
        this.settings = settings;
        this.hikerProperties = hikerProperties;
        this.dataLikersProperties = dataLikersProperties;
        this.clock = clock;
    }

    /** 프로필 요청 1건당 단가 — DataLikers는 HikerAPI와 별개 요금이다. SELF·ACTOR는 프로필 유료 요청 없음. */
    private double profileCostPerRequest() {
        return profileSource.current() == ProfileSource.DATALIKERS
                ? dataLikersProperties.costPerRequestUsd() : hikerProperties.costPerRequestUsd();
    }

    public List<JobCost> estimates() {
        return List.of(discoverEstimate(), qualifyEstimate(), beautyEstimate(),
                similarEstimate(), collectEstimate(), reelsEstimate());
    }

    private JobCost discoverEstimate() {
        String label = "해시태그로 인플루언서 발굴";
        long targets = searchKeywords.findByEnabledTrue().size();
        if (discoverSource.current() == DiscoverSource.ACTOR) {
            return new JobCost("discover", label, List.of("Apify hashtag actor"), targets,
                    0, 0, 0, 0, "Apify 액터 과금 별도(~$0.0023/게시물)");
        }
        long perKeyword = (long) Math.ceil(settings.resultsLimit() / DISCOVER_ITEMS_PER_PAGE);
        long requests = targets * perKeyword;
        double cost = requests * hikerProperties.costPerRequestUsd();
        return new JobCost("discover", label,
                List.of("HikerAPI /v2/hashtag/medias/top (키워드당 페이지 반복)"),
                targets, requests, requests, cost, cost, "페이지당 25~30건 실측 기준 추정");
    }

    private JobCost qualifyEstimate() {
        long targets = Math.min((long) settings.qualifyBatchLimit(),
                influencers.countByStatusAndFollowersIsNull(InfluencerStatus.DISCOVERED));
        List<String> endpoints = new ArrayList<>();
        long perAccount = profileRequestsPerAccount(endpoints);
        String note = profileSource.current() == ProfileSource.ACTOR ? "Apify 액터 과금 별도" : null;
        long requests = targets * perAccount;
        double cost = requests * profileCostPerRequest();
        return new JobCost("qualify", "프로필 스냅샷 · 팔로워 범위 판정",
                endpoints, targets, requests, requests, cost, cost, note);
    }

    private JobCost beautyEstimate() {
        long due = influencers.countByStatusAndBeautyIsNull(InfluencerStatus.QUALIFIED);
        long targets = Math.min((long) settings.beautyBatchLimit(), due);
        return new JobCost("beauty", "뷰티 계정 판정 (로컬 Claude)",
                List.of("로컬 claude CLI — 구독 포함, 유료 API 없음"),
                targets, 0, 0, 0, 0, "판정 재료는 저장된 raw_profile — 인스타그램 호출 없음, 초과분은 다음 실행");
    }

    private JobCost similarEstimate() {
        long due = influencers.countByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(InfluencerStatus.QUALIFIED);
        long targets = Math.min((long) settings.similarBatchLimit(), due);
        long noPk = influencers.countByStatusAndBeautyTrueAndSimilarProcessedAtIsNullAndIgUserIdIsNull(
                InfluencerStatus.QUALIFIED);
        // 배치가 pk 미보유 시드를 몇 명 집을지는 id 순서에 달렸으므로 min(전원 보유)~max(미보유 우선)로 추정
        long min = targets;
        long max = targets + Math.min(targets, noPk);
        return new JobCost("similar", "뷰티 시드의 유사 계정 발굴",
                List.of("HikerAPI /v2/user/suggested/profiles (시드당 1회 · 최대 30계정)",
                        "HikerAPI /v1/user/by/username (pk 미보유 시드만 +1회)"),
                targets, min, max,
                min * hikerProperties.costPerRequestUsd(), max * hikerProperties.costPerRequestUsd(),
                "실측: 호출당 30개 고정 · 기존 DB 대비 신규율 ~85%");
    }

    private JobCost collectEstimate() {
        Instant revisitBefore = RevisitCutoff.boundary(clock, settings.revisitIntervalDays());
        long collectDue = influencers.countBackfillPending() + influencers.countTrackDue(revisitBefore);
        long targets = Math.min((long) settings.collectBatchLimit(), collectDue);
        List<String> endpoints = new ArrayList<>();
        // 프로필 요청은 소스별 단가(DataLikers 별도), 피드/릴스는 HikerAPI 단가 — 나눠서 합산한다.
        long profileReqs = profileRequestsPerAccount(endpoints);
        // 피드는 프로필 원형 내장분만 — 별도 요청 없음(폴백 제거). 프로필 실패는 방문 재시도.
        // 릴스는 REELS 잡으로 분리 — 댓글 수집도 꺼져 있음(comments-enabled), 둘 다 표기 제외
        endpoints.add("최근 피드 — 프로필 원형 내장분만 (별도 요청 없음)");
        long requests = targets * profileReqs;
        double cost = targets * profileReqs * profileCostPerRequest();
        return new JobCost("collect", "게시물을 위한 프로필 수집",
                endpoints, targets, requests, requests, cost, cost,
                "방문당 프로필 1회 — 피드는 원형 내장분, 실패(401 등) 시 방문 재시도. 릴스는 reels 잡 몫");
    }

    private JobCost reelsEstimate() {
        Instant revisitBefore = RevisitCutoff.boundary(clock, settings.revisitIntervalDays());
        long due = influencers.countReelsDue(revisitBefore);
        long targets = Math.min((long) settings.reelsBatchLimit(), due);
        double cost = targets * hikerProperties.costPerRequestUsd();
        return new JobCost("reels", "릴스 수집",
                List.of("HikerAPI /v2/user/clips (계정당 정확히 1회)"),
                targets, targets, targets, cost, cost,
                "뷰티 계정만 · pk 미보유는 스킵(프로필 수집이 채우면 다음 실행) — 초과분은 다음 실행");
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
            case DATALIKERS -> {
                endpoints.add("DataLikers /v1/user/by/username (계정당 1회 · 프록시 우회)");
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
