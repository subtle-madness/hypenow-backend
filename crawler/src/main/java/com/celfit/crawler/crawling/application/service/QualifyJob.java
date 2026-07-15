package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ActorInputs;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판정 잡 — 인플루언서 중심. DISCOVERED 배치를 선정해 프로필 미확보분만 원형으로 수집·저장하고,
 * 전역 팔로워 범위(qualify.min/max-followers)로 QUALIFIED/EXCLUDED를 판정한다.
 * requalify=true면 QUALIFIED·EXCLUDED도 기존 followers(재수집 없이)로 재판정한다.
 */
@Service
public class QualifyJob {

    private static final Logger log = LoggerFactory.getLogger(QualifyJob.class);

    /** raw 원형 수집 시 액터/HikerAPI 호출을 묶는 청크 크기. */
    static final int PROFILE_CHUNK = 50;

    /** failedChunks: 프로필 수집 청크 실패 수 — 0이 아니면 일부 계정이 deferred로 밀린 이유가 실패다. */
    public record Summary(int profiled, int qualified, int excluded, int deferred, int failedChunks) {}

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
        // 1) 판정 가능분 먼저 — followers가 이미 있는(레거시 이관·이전 프로필) DISCOVERED는
        //    API 호출이 없으므로 배치 상한과 무관하게 전부 판정한다.
        List<Influencer> targets = new ArrayList<>(
                influencers.findByStatusAndFollowersIsNotNull(InfluencerStatus.DISCOVERED));

        // 2) 프로필 미확보분은 배치 상한만큼 id 순으로 — 정렬 없는 선정은 매 실행 같은 계정을
        //    다시 뽑거나(진행 정체) 판정 준비된 계정을 영영 안 뽑는 문제가 있었다.
        List<Influencer> toProfile = influencers.findByStatusAndFollowersIsNull(
                InfluencerStatus.DISCOVERED,
                PageRequest.of(0, settings.qualifyBatchLimit(), Sort.by("id")));
        ProfileResult pr = profileMissing(toProfile, trigger);
        int profiled = pr.profiled();
        targets.addAll(toProfile);

        if (requalify) {
            targets.addAll(influencers.findByStatus(InfluencerStatus.QUALIFIED, Pageable.unpaged()));
            targets.addAll(influencers.findByStatus(InfluencerStatus.EXCLUDED, Pageable.unpaged()));
        }

        long min = settings.qualifyMinFollowers(), max = settings.qualifyMaxFollowers();
        int qualified = 0, excluded = 0, deferred = 0;
        int total = targets.size(), i = 0;
        for (Influencer inf : targets) {
            i++;
            Long followers = inf.getFollowers();
            if (followers == null) { deferred++; continue; }   // 프로필 미확보 → 다음 실행 재시도
            boolean pass = followers >= min && followers <= max;
            inf.setStatus(pass ? InfluencerStatus.QUALIFIED : InfluencerStatus.EXCLUDED);
            if (pass) qualified++; else excluded++;
            log.info("판정 ({}/{}) {} — {} (followers={})", i, total, inf.getUsername(),
                    inf.getStatus(), followers);
        }
        return new Summary(profiled, qualified, excluded, deferred, pr.failedChunks());
    }

    private record ProfileResult(int profiled, int failedChunks) {}

    /** followers 미확보 배치의 프로필 수집 — 과거 시도 여부와 무관하게 재시도한다(선정 자체가 미확보 기준). */
    private ProfileResult profileMissing(List<Influencer> toProfile, TriggerType trigger) {
        int profiled = 0, failedChunks = 0;
        for (List<Influencer> chunk : ActorInputs.chunk(toProfile, PROFILE_CHUNK)) {
            List<String> names = chunk.stream().map(Influencer::getUsername).toList();
            CrawlExecutor.Execution ex;
            RawSource source = profileSourceSelector.currentSource();
            try {
                ex = profileSourceSelector.fetchAndSupplement(JobName.QUALIFY, names, trigger);
            } catch (ApifyException e) {
                failedChunks++;  // crawl_run에 FAILED 기록됨 — 해당 청크 계정은 다음 실행 재시도
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
                String userId = ProfileExtractor.userId(item, source);
                if (userId != null) inf.setIgUserId(userId);   // collect 열거 파라미터 — 폴백용 보존
                inf.setLastProfiledAt(clock.instant());
                profiled++;
            }
        }
        return new ProfileResult(profiled, failedChunks);
    }
}
