package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ActorInputs;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 재스냅샷 잡 — 캡션 없는 재료(HIKER_MOBILE·DATALIKERS)로 비뷰티 판정된 계정의 프로필을
 * 로컬 GQL(SELF)로 다시 수집한다. 캡션이 채워지면 beauty rejudge가 재판정해 뷰티로 구제될 수
 * 있다(2026-07-16 실험: 캡션 없는 비뷰티의 ~30%가 캡션 제공 시 뷰티로 뒤집힘). 재수집이 끝난
 * 계정은 최신 스냅샷이 SELF_GQL이 되어 다음 실행 선정에서 자연히 빠진다.
 */
@Service
public class ResnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(ResnapshotJob.class);

    /** 캡션이 payload에 아예 없는 소스 — 이 소스가 최신인 계정만 재수집 대상. */
    static final List<RawSource> CAPTIONLESS_SOURCES = List.of(RawSource.HIKER_MOBILE, RawSource.DATALIKERS);

    /** 로컬 GQL 순차 호출을 묶는 청크 크기 — 청크 단위 crawl_run·커밋(실패 격리). */
    static final int PROFILE_CHUNK = 50;

    public record Summary(int snapshotted, int skippedPrivate, int failedChunks) {}

    private final InfluencerRepository influencers;
    private final RawProfileRepository rawProfiles;
    private final SelfProfileFetcher fetcher;
    private final SettingsService settings;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public ResnapshotJob(InfluencerRepository influencers, RawProfileRepository rawProfiles,
                         SelfProfileFetcher fetcher, SettingsService settings, Clock clock,
                         TransactionTemplate txTemplate) {
        this.influencers = influencers;
        this.rawProfiles = rawProfiles;
        this.fetcher = fetcher;
        this.settings = settings;
        this.clock = clock;
        this.txTemplate = txTemplate;
    }

    /**
     * 청크 1개 = 트랜잭션 1개 — fetch(계정당 GET 1회 순차, 청크당 수 분)는 트랜잭션 밖에서 하고
     * 적용만 감싼다(qualify와 동일한 이유 — 커넥션 미점유·실패 격리, QualifyJobIntegrationTest 참고).
     */
    public Summary run(TriggerType trigger) {
        List<Influencer> targets = influencers.findResnapshotTargets(
                InfluencerStatus.QUALIFIED, Influencer.BEAUTY_SOURCE_CLAUDE,
                CAPTIONLESS_SOURCES, PageRequest.of(0, settings.resnapshotBatchLimit()));

        // 비공개 계정은 GQL 타임라인이 비어 캡션을 얻을 수 없다 — 요청을 쓰지 않고 뺀다.
        // 최신 스냅샷이 그대로라 다음 실행에 재선정되지만, 재료 조회(DB)만 반복될 뿐 요청 낭비는 없다.
        List<Influencer> fetchable = new ArrayList<>();
        int skippedPrivate = 0;
        for (Influencer inf : targets) {
            var latest = rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(inf.getId());
            if (latest.isPresent()
                    && ProfileExtractor.isPrivate(latest.get().getPayload(), latest.get().getSource())) {
                skippedPrivate++;
                continue;
            }
            fetchable.add(inf);
        }

        int snapshotted = 0, failedChunks = 0;
        List<List<Influencer>> chunks = ActorInputs.chunk(fetchable, PROFILE_CHUNK);
        log.info("재스냅샷 시작 — 대상 {}명(비공개 스킵 {}), 청크 {}개", fetchable.size(), skippedPrivate, chunks.size());
        for (List<Influencer> chunk : chunks) {
            List<String> names = chunk.stream().map(Influencer::getUsername).toList();
            CrawlExecutor.Execution ex;
            try {
                ex = fetcher.fetch(JobName.RESNAPSHOT, names, trigger);  // 트랜잭션 밖
            } catch (RuntimeException e) {
                failedChunks++;  // 해당 청크 계정은 최신 스냅샷이 그대로 — 다음 실행 재선정
                log.warn("재스냅샷 청크 실패({}명): {}", chunk.size(), e.getMessage());
                continue;
            }
            snapshotted += txTemplate.execute(status -> applyChunk(chunk, ex));
        }
        return new Summary(snapshotted, skippedPrivate, failedChunks);
    }

    /** 청크 1개 적용(트랜잭션 안) — raw 원형 저장 + followers·igUserId 백필(qualify와 동일 규칙). */
    private int applyChunk(List<Influencer> chunk, CrawlExecutor.Execution ex) {
        RawSource source = fetcher.rawSource();
        Map<String, Influencer> byName = chunk.stream()
                .collect(Collectors.toMap(Influencer::getUsername, i -> i));
        // 404로 판명된 계정(삭제·개명) — 소프트 딜리트로 종결, 재선정을 끊는다
        for (String gone : ex.notFound()) {
            Influencer inf = byName.get(gone);
            if (inf == null) continue;
            inf.setStatus(InfluencerStatus.DELETED);
            influencers.save(inf);
            log.info("resnapshot 계정 소멸(404) — DELETED: {}", gone);
        }
        int snapshotted = 0;
        for (Map<String, Object> item : ex.items()) {
            String username = ProfileExtractor.username(item, source);
            Influencer inf = username != null ? byName.get(username) : null;
            if (inf == null) continue;  // 응답 누락·이름 변경 — 다음 실행 재선정
            RawProfile rp = new RawProfile(inf.getId(), ex.runId(), source, item, clock.instant());
            rp.setUsername(username);
            rp.setFollowers(ProfileExtractor.followers(item, source));
            rawProfiles.save(rp);
            if (rp.getFollowers() != null) inf.setFollowers(rp.getFollowers());
            String userId = ProfileExtractor.userId(item, source);
            if (userId != null) inf.setIgUserId(userId);
            inf.setLastProfiledAt(clock.instant());
            influencers.save(inf);   // detached — 명시 save
            snapshotted++;
        }
        return snapshotted;
    }
}
