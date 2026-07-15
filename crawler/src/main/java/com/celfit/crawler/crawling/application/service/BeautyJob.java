package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ActorInputs;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 뷰티 판정 잡 — QUALIFIED 중 미판정분의 최신 raw_profile 텍스트 재료(이름·카테고리·bio)를
 * 로컬 Claude에 배치로 넘겨 beauty를 저장한다. 인스타그램 API 호출 없음(비용 $0).
 * rejudge=true면 CLAUDE 판정분도 재판정한다 — MANUAL(수동)은 선정에서 빠져 절대 덮이지 않는다.
 * beauty=true는 SIMILAR 잡의 시드 자격이 된다.
 */
@Service
public class BeautyJob {

    private static final Logger log = LoggerFactory.getLogger(BeautyJob.class);

    /** Claude 1회 호출에 넘기는 프로필 수 — 응답 길이·타임아웃(120s)과의 균형. */
    static final int JUDGE_CHUNK = 50;

    public record Summary(int judgedBeauty, int judgedNotBeauty, int skippedNoProfile, int failedBatches) {}

    private final InfluencerRepository influencers;
    private final RawProfileRepository rawProfiles;
    private final BeautyJudge judge;
    private final TransactionTemplate txTemplate;

    public BeautyJob(InfluencerRepository influencers, RawProfileRepository rawProfiles, BeautyJudge judge,
                     TransactionTemplate txTemplate) {
        this.influencers = influencers;
        this.rawProfiles = rawProfiles;
        this.judge = judge;
        this.txTemplate = txTemplate;
    }

    /**
     * 배치 전체가 아니라 판정 배치(청크) 1회 = 트랜잭션 1개로 감싼다 — judge.judge(chunk)는 로컬 Claude
     * CLI 호출로 최대 120초 걸릴 수 있어 트랜잭션 밖에서 실행하고(커넥션을 idle-in-transaction으로
     * 붙들지 않기 위함), 판정 결과 적용만 트랜잭션으로 감싼다. 한 배치의 RuntimeException이 앞선
     * 배치들의 커밋을 롤백시키지 않는다.
     */
    public Summary run(TriggerType trigger, boolean rejudge) {
        List<Influencer> targets = new ArrayList<>(
                influencers.findByStatusAndBeautyIsNull(InfluencerStatus.QUALIFIED));
        if (rejudge) {
            targets.addAll(influencers.findByStatusAndBeautySource(
                    InfluencerStatus.QUALIFIED, Influencer.BEAUTY_SOURCE_CLAUDE));
        }

        // 판정 재료 준비 — raw_profile이 아직 없으면 판정 불가(qualify가 언젠가 채우면 재시도)
        List<BeautyJudge.ProfileCard> cards = new ArrayList<>();
        Map<String, Influencer> byUsername = new HashMap<>();
        int skipped = 0;
        for (Influencer inf : targets) {
            if (byUsername.containsKey(inf.getUsername())) continue;  // 두 선정 쿼리 중복 방어
            var rp = rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(inf.getId());
            if (rp.isEmpty()) { skipped++; continue; }
            RawProfile p = rp.get();
            cards.add(new BeautyJudge.ProfileCard(inf.getUsername(),
                    ProfileExtractor.fullName(p.getPayload(), p.getSource()),
                    ProfileExtractor.category(p.getPayload(), p.getSource()),
                    ProfileExtractor.biography(p.getPayload(), p.getSource())));
            byUsername.put(inf.getUsername(), inf);
        }

        int beauty = 0, notBeauty = 0, failedBatches = 0;
        List<List<BeautyJudge.ProfileCard>> chunks = ActorInputs.chunk(cards, JUDGE_CHUNK);
        int total = chunks.size(), i = 0;
        for (List<BeautyJudge.ProfileCard> chunk : chunks) {
            i++;
            List<BeautyJudge.Verdict> verdicts;
            try {
                verdicts = judge.judge(chunk);  // 트랜잭션 밖 — 최대 120초 CLI 호출 동안 커넥션 미점유
            } catch (ApifyException e) {
                failedBatches++;  // 해당 배치 계정은 beauty NULL 유지 — 다음 실행 재시도
                log.warn("뷰티 판정 배치 실패 ({}/{}, {}명): {}", i, total, chunk.size(), e.getMessage());
                continue;
            }
            ChunkResult r = txTemplate.execute(status -> applyVerdicts(verdicts, byUsername));
            beauty += r.beauty();
            notBeauty += r.notBeauty();
            log.info("뷰티 판정 배치 ({}/{}) 완료 — 누계 뷰티 {} / 비뷰티 {}", i, total, beauty, notBeauty);
        }
        return new Summary(beauty, notBeauty, skipped, failedBatches);
    }

    private record ChunkResult(int beauty, int notBeauty) {}

    /**
     * 판정 결과 적용(트랜잭션 안). targets 조회가 트랜잭션 밖(레포 자체 트랜잭션)에서 이뤄져 Influencer가
     * detached 상태다 — 세터만으로는 저장되지 않으므로 influencers.save(inf) 명시 호출이 필수다
     * (CollectJob이 방문 단위 트랜잭션 전환 때 겪은 회귀와 동일 — CollectJobIntegrationTest 참고).
     */
    private ChunkResult applyVerdicts(List<BeautyJudge.Verdict> verdicts, Map<String, Influencer> byUsername) {
        int beauty = 0, notBeauty = 0;
        for (BeautyJudge.Verdict v : verdicts) {
            Influencer inf = byUsername.get(v.username());
            if (inf == null) continue;  // 응답이 지어낸 username — 무시
            inf.setBeauty(v.beauty());
            inf.setBeautySource(Influencer.BEAUTY_SOURCE_CLAUDE);
            inf.setBeautyReason(v.reason());
            influencers.save(inf);
            if (v.beauty()) beauty++; else notBeauty++;
        }
        return new ChunkResult(beauty, notBeauty);
    }
}
