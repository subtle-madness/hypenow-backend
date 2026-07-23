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
import org.springframework.transaction.support.TransactionTemplate;

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
    private final JobStopFlag stopFlag;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public SimilarJob(InfluencerRepository influencers, InfluencerDiscoveryRepository discoveries,
                      HikerSuggestedSupplement suggested, HikerUserResolver resolver,
                      CrawlExecutor executor, SettingsService settings, JobStopFlag stopFlag, Clock clock,
                      TransactionTemplate txTemplate) {
        this.influencers = influencers;
        this.discoveries = discoveries;
        this.suggested = suggested;
        this.resolver = resolver;
        this.executor = executor;
        this.settings = settings;
        this.stopFlag = stopFlag;
        this.clock = clock;
        this.txTemplate = txTemplate;
    }

    /**
     * 배치 전체가 아니라 시드 1개 = 트랜잭션 1개로 감싼다 — 최대 50시드 × HTTP 대기 내내 커넥션을
     * idle-in-transaction으로 붙들지 않기 위함 + 한 시드의 RuntimeException이 앞선 시드들의
     * 마킹·백필·upsert·crawl_run 커밋까지 롤백시키지 않기 위함. CrawlExecutor는 호출자 트랜잭션에
     * 합류하므로(REQUIRES_NEW 아님) 시드당 run 기록+아카이브+upsert가 한 커밋 단위로 묶인다(의도됨).
     */
    public Summary run(TriggerType trigger) {
        List<Influencer> seeds = influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                InfluencerStatus.QUALIFIED,
                PageRequest.of(0, settings.similarBatchLimit(), Sort.by("id")));
        int processed = 0, newInf = 0, known = 0, ineligible = 0, failed = 0;
        int total = seeds.size(), i = 0;
        for (Influencer seed : seeds) {
            if (stopFlag.isRequested(JobName.SIMILAR)) {
                log.info("similar 중지 요청 — 잔여 시드 건너뛰고 조기 종료 ({}/{} 시드 처리)", i, total);
                break;
            }
            i++;
            int idx = i;
            SeedResult r = txTemplate.execute(status -> processSeed(seed, trigger, idx, total));
            processed += r.processed();
            newInf += r.newInf();
            known += r.known();
            ineligible += r.ineligible();
            failed += r.failed();
        }
        return new Summary(processed, newInf, known, ineligible, failed);
    }

    private record SeedResult(int processed, int newInf, int known, int ineligible, int failed) {}

    /**
     * 시드 1개 처리(트랜잭션 안). ApifyException은 여기서 잡아야 한다 — 트랜잭션 밖으로 던지면
     * CrawlExecutor가 이미 저장한 crawl_run FAILED 기록까지 롤백된다.
     * seed는 findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull이 트랜잭션 밖(레포 자체 트랜잭션)에서
     * 조회돼 detached 상태다 — 세터만으로는 저장 안 되므로 influencers.save(seed) 명시 호출이 필수
     * (pk 백필도 함께 영속된다). CollectJob이 방문 단위 트랜잭션 전환 때 겪은 회귀와 동일 — 참고: CollectJobIntegrationTest.
     */
    private SeedResult processSeed(Influencer seed, TriggerType trigger, int i, int total) {
        // 'chaining 불가' 판정은 콜백 안에서 빈 결과로 흡수한다 — 예외로 내보내면 CrawlExecutor가
        // run을 FAILED로 마감해, 양성 케이스가 실패 통계·어드민 FAILED 배지를 오염시킨다
        // (ReelsJob의 '릴스 없음' 404와 동일 패턴). 요청은 나갔으므로 requestCount는 유지한다.
        var ineligible = new java.util.concurrent.atomic.AtomicBoolean();
        CrawlExecutor.Execution ex;
        try {
            ex = executor.execute(JobName.SIMILAR, trigger, KEYWORD_PREFIX + seed.getUsername(),
                    seed.getUsername(), LABEL, () -> {
                        try {
                            return fetchForSeed(seed);
                        } catch (ApifyException e) {
                            if (e.getMessage() != null && e.getMessage().contains(INELIGIBLE_MARK)) {
                                ineligible.set(true);
                                return new ApifyResult(null, 1, List.of());
                            }
                            throw e;
                        }
                    });
        } catch (ApifyException e) {
            // crawl_run FAILED 기록됨 — 마킹 없이 다음 실행 재시도
            // pk 백필만 영속 — 실패 시드도 다음 실행에서 재해석 비용을 내지 않도록
            if (seed.getIgUserId() != null) {
                influencers.save(seed);
            }
            log.warn("유사 발굴 ({}/{}) {} — 실패: {}", i, total, seed.getUsername(), e.getMessage());
            return new SeedResult(0, 0, 0, 0, 1);
        }
        if (ineligible.get()) {
            seed.setSimilarProcessedAt(clock.instant());  // 수확 불가 확정 — 재시도 안 함
            influencers.save(seed);
            log.info("유사 발굴 ({}/{}) {} — chaining 불가, 수확 불가로 마킹", i, total, seed.getUsername());
            return new SeedResult(0, 0, 0, 1, 0);
        }
        Set<String> seen = new HashSet<>();
        int newInf = 0, known = 0;
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
        influencers.save(seed);
        log.info("유사 발굴 ({}/{}) {} — 이번 시드 {}건, 신규 {}건", i, total, seed.getUsername(), seen.size(), newInf);
        return new SeedResult(1, newInf, known, 0, 0);
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
