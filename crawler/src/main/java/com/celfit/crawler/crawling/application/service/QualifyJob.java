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
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final JobStopFlag stopFlag;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public QualifyJob(InfluencerRepository influencers, RawProfileRepository rawProfiles,
                      ProfileSourceSelector profileSourceSelector, SettingsService settings,
                      JobStopFlag stopFlag, Clock clock, TransactionTemplate txTemplate) {
        this.influencers = influencers;
        this.rawProfiles = rawProfiles;
        this.profileSourceSelector = profileSourceSelector;
        this.settings = settings;
        this.stopFlag = stopFlag;
        this.clock = clock;
        this.txTemplate = txTemplate;
    }

    /**
     * 배치 전체가 아니라 프로필 청크 1개 = 트랜잭션 1개로 감싼다 — 실행 전체 단일 트랜잭션은
     * HTTP 대기 내내 커넥션을 점유하고, 실행 후반의 예외·프로세스 종료가 앞선 청크의 확보분까지
     * 통째로 롤백시켰다(2026-07-16 실측: 앱 종료로 확보 30건 유실). fetch는 트랜잭션 밖에서 하고
     * 적용만 감싼다. 조회가 트랜잭션 밖이라 엔티티가 detached — 세터만으로는 저장되지 않으므로
     * 명시 save 필수(다른 잡들이 겪은 회귀와 동일 — CollectJobIntegrationTest 참고).
     */
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

        // 3) 판정 적용 — DB 전용이라 빠르고, 프로필 청크 커밋과는 분리된 트랜잭션
        JudgeResult jr = txTemplate.execute(status -> judge(targets));
        return new Summary(profiled, jr.qualified(), jr.excluded(), jr.deferred(), pr.failedChunks());
    }

    private record JudgeResult(int qualified, int excluded, int deferred) {}

    private JudgeResult judge(List<Influencer> targets) {
        long min = settings.qualifyMinFollowers(), max = settings.qualifyMaxFollowers();
        int qualified = 0, excluded = 0, deferred = 0;
        int total = targets.size(), i = 0;
        for (Influencer inf : targets) {
            i++;
            if (inf.getStatus() == InfluencerStatus.DELETED) continue;  // 404 소프트 딜리트 — 판정 제외
            Long followers = inf.getFollowers();
            if (followers == null) { deferred++; continue; }   // 프로필 미확보 → 다음 실행 재시도
            boolean pass = followers >= min && followers <= max;
            inf.setStatus(pass ? InfluencerStatus.QUALIFIED : InfluencerStatus.EXCLUDED);
            influencers.save(inf);   // detached — 명시 save 없이는 판정이 저장되지 않는다
            if (pass) qualified++; else excluded++;
            log.info("판정 ({}/{}) {} — {} (followers={})", i, total, inf.getUsername(),
                    inf.getStatus(), followers);
        }
        return new JudgeResult(qualified, excluded, deferred);
    }

    private record ProfileResult(int profiled, int failedChunks) {}

    /**
     * followers 미확보 배치의 프로필 수집 — 과거 시도 여부와 무관하게 재시도한다(선정 자체가 미확보 기준).
     * 청크 실패는 ApifyException뿐 아니라 모든 RuntimeException을 격리한다 — 한 청크의 예외가
     * 앞선 청크들의 커밋을 무효화하거나 잡 전체를 죽이지 않는다.
     */
    private ProfileResult profileMissing(List<Influencer> toProfile, TriggerType trigger) {
        int profiled = 0, failedChunks = 0;
        for (List<Influencer> chunk : ActorInputs.chunk(toProfile, PROFILE_CHUNK)) {
            if (stopFlag.isRequested(JobName.QUALIFY)) {
                // 잔여 청크만 중단 — 이미 확보한 프로필의 판정(judge, DB 전용·즉시)은 그대로 진행된다
                log.info("qualify 중지 요청 — 잔여 프로필 청크 건너뛰고 조기 종료");
                break;
            }
            List<String> names = chunk.stream().map(Influencer::getUsername).toList();
            CrawlExecutor.Execution ex;
            RawSource source = profileSourceSelector.currentSource();
            try {
                ex = profileSourceSelector.fetchAndSupplement(JobName.QUALIFY, names, trigger);  // 트랜잭션 밖
            } catch (RuntimeException e) {
                failedChunks++;  // ApifyException이면 crawl_run에 FAILED 기록됨 — 해당 청크 계정은 다음 실행 재시도
                log.warn("qualify 프로필 청크 실패({}명): {}", chunk.size(), e.getMessage());
                continue;
            }
            profiled += txTemplate.execute(status -> applyChunk(chunk, ex, source));
        }
        return new ProfileResult(profiled, failedChunks);
    }

    /** 청크 1개 적용(트랜잭션 안) — raw 원형 저장 + followers·igUserId 백필. */
    private int applyChunk(List<Influencer> chunk, CrawlExecutor.Execution ex, RawSource batchSource) {
        Map<String, Influencer> byName = chunk.stream()
                .collect(Collectors.toMap(Influencer::getUsername, i -> i));
        int profiled = 0;
        // 404로 판명된 계정(삭제·개명) — 소프트 딜리트로 종결, 매 실행 재선정·재과금을 끊는다
        for (String gone : ex.notFound()) {
            Influencer inf = byName.get(gone);
            if (inf == null) continue;
            inf.setStatus(InfluencerStatus.DELETED);
            influencers.save(inf);
            log.info("qualify 계정 소멸(404) — DELETED: {}", gone);
        }
        // 양쪽 소스 모두 빈 응답 확인(SELF 연속 임계 도달 + Hiker 폴백도 빈 응답) — 즉시 소프트
        // 딜리트로 종결한다. 이 종결이 없으면 followers-NULL 재선정이 무한 반복돼 빈 응답 유료
        // 폴백을 열 수 없다(컴포지트 페처 클래스 주석). collect의 30일 유예와 달리 즉시인 이유:
        // DISCOVERED는 아직 리드일 뿐이고, 소프트 딜리트라 재발굴되면 다시 들어온다.
        for (String dormant : ex.confirmedEmpty()) {
            Influencer inf = byName.get(dormant);
            if (inf == null) continue;
            inf.setStatus(InfluencerStatus.DELETED);
            influencers.save(inf);
            log.info("qualify 양쪽 소스 빈 응답 확인(비활성화·숨김) — DELETED: {}", dormant);
        }
        for (Map<String, Object> item : ex.items()) {
            // 컴포지트(400 → Hiker 폴백) 배치는 아이템별 원형이 섞인다 — 셰이프로 실제 소스 감지
            RawSource source = ProfileExtractor.detect(item, batchSource);
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
            influencers.save(inf);   // detached — 명시 save
            profiled++;
        }
        return profiled;
    }
}
