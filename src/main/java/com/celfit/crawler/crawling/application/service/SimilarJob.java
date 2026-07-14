package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.InfluencerDiscoveryRepository;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerDiscovery;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 유사 계정 발굴 잡 — 뷰티 시드(QUALIFIED·beauty=true·미수확)마다 HikerAPI suggested
 * profiles(호출당 최대 30개)를 받아 DISCOVERED로 upsert한다. 응답에는 팔로워·bio가 없으므로
 * 사전 필터 없음 — 팔로워 판정은 qualify, 뷰티 판정은 beauty 잡 몫(통과하면 다시 시드가 된다).
 * 발굴 출처는 influencer_discovery("유사:{시드}") 텍스트 스냅샷으로 남긴다.
 */
@Service
public class SimilarJob {

    private static final Logger log = LoggerFactory.getLogger(SimilarJob.class);

    static final String LABEL = "hiker-suggested-profiles";
    static final String KEYWORD_PREFIX = "유사:";
    /** HikerAPI가 추천 체이닝을 막아둔 계정의 응답 표식 — 재시도 무의미, 수확 불가로 마킹. */
    static final String INELIGIBLE_MARK = "Not eligible for chaining";

    public record Summary(int processedSeeds, int newInfluencers, int knownInfluencers,
                          int ineligibleSeeds, int failedSeeds) {}

    private final InfluencerRepository influencers;
    private final InfluencerDiscoveryRepository discoveries;
    private final HikerSuggestedSupplement suggested;
    private final HikerUserResolver resolver;
    private final CrawlExecutor executor;
    private final SettingsService settings;
    private final Clock clock;

    public SimilarJob(InfluencerRepository influencers, InfluencerDiscoveryRepository discoveries,
                      HikerSuggestedSupplement suggested, HikerUserResolver resolver,
                      CrawlExecutor executor, SettingsService settings, Clock clock) {
        this.influencers = influencers;
        this.discoveries = discoveries;
        this.suggested = suggested;
        this.resolver = resolver;
        this.executor = executor;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public Summary run(TriggerType trigger) {
        List<Influencer> seeds = influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                InfluencerStatus.QUALIFIED,
                PageRequest.of(0, settings.similarBatchLimit(), Sort.by("id")));
        int processed = 0, newInf = 0, known = 0, ineligible = 0, failed = 0;
        int total = seeds.size(), i = 0;
        for (Influencer seed : seeds) {
            i++;
            CrawlExecutor.Execution ex;
            try {
                ex = executor.execute(JobName.SIMILAR, trigger, KEYWORD_PREFIX + seed.getUsername(),
                        seed.getUsername(), LABEL, () -> fetchForSeed(seed));
            } catch (ApifyException e) {
                if (e.getMessage() != null && e.getMessage().contains(INELIGIBLE_MARK)) {
                    seed.setSimilarProcessedAt(clock.instant());  // 수확 불가 확정 — 재시도 안 함
                    ineligible++;
                    log.info("유사 발굴 ({}/{}) {} — chaining 불가, 수확 불가로 마킹", i, total, seed.getUsername());
                } else {
                    failed++;  // crawl_run FAILED 기록됨 — 마킹 없이 다음 실행 재시도
                    log.warn("유사 발굴 ({}/{}) {} — 실패: {}", i, total, seed.getUsername(), e.getMessage());
                }
                continue;
            }
            Set<String> seen = new HashSet<>();
            for (Map<String, Object> item : ex.items()) {
                String username = item.get("username") instanceof String s && !s.isBlank() ? s : null;
                if (username == null || username.equalsIgnoreCase(seed.getUsername())
                        || !seen.add(username.toLowerCase())) continue;
                var existing = influencers.findByUsername(username);
                Influencer inf = existing.orElseGet(() -> influencers.save(new Influencer(username)));
                if (existing.isPresent()) known++; else newInf++;
                // 신규·기존 모두 출처 기록(append-only) — discover의 관례와 동일
                discoveries.save(new InfluencerDiscovery(
                        inf.getId(), KEYWORD_PREFIX + seed.getUsername(), null, clock.instant()));
            }
            seed.setSimilarProcessedAt(clock.instant());
            processed++;
            log.info("유사 발굴 ({}/{}) {} — 이번 시드 {}건, 신규 누계 {}", i, total,
                    seed.getUsername(), seen.size(), newInf);
        }
        return new Summary(processed, newInf, known, ineligible, failed);
    }

    /**
     * 시드의 pk 확보(없으면 유료 1요청으로 해석해 ig_user_id 백필) 후 suggested 호출.
     * requestCount에 실제 유료 요청 수(1 또는 2)를 기록해 비용 추적을 정확히 한다.
     */
    private ApifyResult fetchForSeed(Influencer seed) {
        int requests = 0;
        String pk = seed.getIgUserId();
        if (pk == null || pk.isBlank()) {
            requests++;
            pk = resolver.resolvePk(seed.getUsername());
            if (pk == null) throw new ApifyException("pk 해석 실패: " + seed.getUsername());
            seed.setIgUserId(pk);  // 백필 — collect·재실행에서 재해석 없음
        }
        requests++;
        return new ApifyResult(null, requests, suggested.fetch(pk).users());
    }
}
