package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ActorInputs;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판정 잡 — 인플루언서 중심. DISCOVERED 배치를 선정해 프로필 미확보분만 원형으로 수집·저장하고,
 * 전역 팔로워 범위(qualify.min/max-followers)로 QUALIFIED/EXCLUDED를 판정한다.
 * requalify=true면 QUALIFIED·EXCLUDED도 기존 followers(재수집 없이)로 재판정한다.
 */
@Service
public class QualifyJob {

    /** raw 원형 수집 시 액터/HikerAPI 호출을 묶는 청크 크기. */
    static final int PROFILE_CHUNK = 50;

    public record Summary(int profiled, int qualified, int excluded, int deferred) {}

    private final InfluencerRepository influencers;
    private final RawProfileRepository rawProfiles;
    private final ProfileSourceSelector profileSourceSelector;
    private final SettingsService settings;
    private final Clock clock;

    public QualifyJob(InfluencerRepository influencers, RawProfileRepository rawProfiles,
                      ProfileSourceSelector profileSourceSelector, SettingsService settings, Clock clock) {
        this.influencers = influencers;
        this.rawProfiles = rawProfiles;
        this.profileSourceSelector = profileSourceSelector;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public Summary run(TriggerType trigger, boolean requalify) {
        List<Influencer> targets = new ArrayList<>(influencers.findByStatus(
                InfluencerStatus.DISCOVERED, PageRequest.of(0, settings.qualifyBatchLimit())));

        int profiled = profileMissing(targets, trigger);

        if (requalify) {
            targets.addAll(influencers.findByStatus(InfluencerStatus.QUALIFIED, Pageable.unpaged()));
            targets.addAll(influencers.findByStatus(InfluencerStatus.EXCLUDED, Pageable.unpaged()));
        }

        long min = settings.qualifyMinFollowers(), max = settings.qualifyMaxFollowers();
        int qualified = 0, excluded = 0, deferred = 0;
        for (Influencer inf : targets) {
            Long followers = inf.getFollowers();
            if (followers == null) { deferred++; continue; }   // 프로필 미확보 → 다음 실행 재시도
            boolean pass = followers >= min && followers <= max;
            inf.setStatus(pass ? InfluencerStatus.QUALIFIED : InfluencerStatus.EXCLUDED);
            if (pass) qualified++; else excluded++;
        }
        return new Summary(profiled, qualified, excluded, deferred);
    }

    private int profileMissing(List<Influencer> targets, TriggerType trigger) {
        List<Influencer> toProfile = targets.stream()
                .filter(i -> i.getLastProfiledAt() == null).toList();
        int profiled = 0;
        for (List<Influencer> chunk : ActorInputs.chunk(toProfile, PROFILE_CHUNK)) {
            List<String> names = chunk.stream().map(Influencer::getUsername).toList();
            CrawlExecutor.Execution ex;
            RawSource source = profileSourceSelector.currentSource();
            try {
                ex = profileSourceSelector.fetchAndSupplement(names, trigger);
            } catch (ApifyException e) {
                continue;
            }
            Map<String, Influencer> byName = chunk.stream()
                    .collect(Collectors.toMap(Influencer::getUsername, i -> i));
            for (Map<String, Object> item : ex.items()) {
                String username = ProfileExtractor.username(item, source);
                Influencer inf = username != null ? byName.get(username) : null;
                if (inf == null) continue;
                RawProfile rp = new RawProfile(inf.getId(), ex.runId(), source, item, clock.instant());
                rp.setUsername(username);
                rp.setFollowers(ProfileExtractor.followers(item, source));
                rawProfiles.save(rp);
                inf.setFollowers(rp.getFollowers());
                inf.setLastProfiledAt(clock.instant());
                profiled++;
            }
        }
        return profiled;
    }
}
