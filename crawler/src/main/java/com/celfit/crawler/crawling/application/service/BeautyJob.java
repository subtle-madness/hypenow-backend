package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ActorInputs;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawMediaPageRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.domain.BeautyClass;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 뷰티 판정 잡 — QUALIFIED 중 미판정분의 최신 raw_profile 텍스트 재료(이름·카테고리·bio)를
 * 로컬 Claude에 배치로 넘겨 beauty_class와 파생 boolean을 저장한다. 인스타그램 API 호출 없음(비용 $0).
 * rejudge=true면 판정 후 재료(raw_profile)가 갱신된 CLAUDE 비뷰티 판정분을 재판정한다 —
 * MANUAL(수동)은 선정에서 빠져 절대 덮이지 않는다.
 * beauty=true는 SIMILAR 잡의 시드 자격이 된다.
 */
@Service
public class BeautyJob {

    private static final Logger log = LoggerFactory.getLogger(BeautyJob.class);

    /** Claude 1회 호출에 넘기는 프로필 수 — 응답 길이·타임아웃(120s)과의 균형. */
    static final int JUDGE_CHUNK = 50;

    /** 카드에 담는 최근 게시물 캡션 수 — 프롬프트 크기(50명 × 캡션)와 판정 정확도의 균형. */
    static final int CAPTION_COUNT = 5;

    /** 캡션 1개당 최대 길이(문자) — 캡션 앞부분에 주제가 드러나므로 뒷부분은 잘라도 판정에 충분. */
    static final int CAPTION_MAX_CHARS = 100;

    public record Summary(int judgedBeauty, int judgedService, int judgedForeign, int judgedNotBeauty,
                          int skippedNoProfile, int failedBatches) {}

    private final InfluencerRepository influencers;
    private final RawProfileRepository rawProfiles;
    private final RawMediaPageRepository rawMediaPages;
    private final BeautyJudge judge;
    private final SettingsService settings;
    private final JobStopFlag stopFlag;
    private final java.time.Clock clock;
    private final TransactionTemplate txTemplate;

    public BeautyJob(InfluencerRepository influencers, RawProfileRepository rawProfiles,
                     RawMediaPageRepository rawMediaPages, BeautyJudge judge,
                     SettingsService settings, JobStopFlag stopFlag, java.time.Clock clock,
                     TransactionTemplate txTemplate) {
        this.influencers = influencers;
        this.rawProfiles = rawProfiles;
        this.rawMediaPages = rawMediaPages;
        this.judge = judge;
        this.settings = settings;
        this.stopFlag = stopFlag;
        this.clock = clock;
        this.txTemplate = txTemplate;
    }

    /**
     * 배치 전체가 아니라 판정 배치(청크) 1회 = 트랜잭션 1개로 감싼다 — judge.judge(chunk)는 로컬 Claude
     * CLI 호출로 최대 120초 걸릴 수 있어 트랜잭션 밖에서 실행하고(커넥션을 idle-in-transaction으로
     * 붙들지 않기 위함), 판정 결과 적용만 트랜잭션으로 감싼다. 한 배치의 RuntimeException이 앞선
     * 배치들의 커밋을 롤백시키지 않는다.
     */
    public Summary run(TriggerType trigger, boolean rejudge) {
        // 배치 한도(beauty.batch-limit) — 미판정 우선 선정, rejudge는 남은 한도만 채운다(초과분은 다음 실행)
        int limit = settings.beautyBatchLimit();
        List<Influencer> targets = new ArrayList<>(influencers.findByStatusAndBeautyIsNull(
                InfluencerStatus.QUALIFIED, PageRequest.of(0, limit, Sort.by("id"))));
        if (rejudge && targets.size() < limit) {
            // 재료(raw_profile)가 판정 후 갱신된 비뷰티만 — 재료가 그대로면 같은 판정만 반복한다.
            // 오래된 판정 우선(쿼리 정렬) — 실패 배치가 옛 판정 시각으로 남아 먼저 재시도된다
            targets.addAll(influencers.findRejudgeTargets(
                    InfluencerStatus.QUALIFIED, Influencer.BEAUTY_SOURCE_CLAUDE,
                    PageRequest.of(0, limit - targets.size())));
        }

        // 판정 재료 준비 — raw_profile이 아직 없으면 판정 불가(qualify가 언젠가 채우면 재시도)
        List<BeautyJudge.ProfileCard> cards = new ArrayList<>();
        Map<String, Influencer> byUsername = new HashMap<>();
        Map<String, Integer> captionCounts = new HashMap<>();
        int skipped = 0;
        for (Influencer inf : targets) {
            if (byUsername.containsKey(inf.getUsername())) continue;  // 두 선정 쿼리 중복 방어
            var rp = rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(inf.getId());
            if (rp.isEmpty()) { skipped++; continue; }
            RawProfile p = rp.get();
            List<String> captions = trimCaptions(
                    ProfileExtractor.recentCaptions(p.getPayload(), p.getSource()));
            if (captions.isEmpty()) captions = trimCaptions(mediaCaptions(inf.getId()));
            cards.add(new BeautyJudge.ProfileCard(inf.getUsername(),
                    ProfileExtractor.fullName(p.getPayload(), p.getSource()),
                    ProfileExtractor.category(p.getPayload(), p.getSource()),
                    ProfileExtractor.biography(p.getPayload(), p.getSource()),
                    captions));
            byUsername.put(inf.getUsername(), inf);
            captionCounts.put(inf.getUsername(), captions.size());
        }

        int beauty = 0, service = 0, foreign = 0, notBeauty = 0, failedBatches = 0;
        List<List<BeautyJudge.ProfileCard>> chunks = ActorInputs.chunk(cards, JUDGE_CHUNK);
        log.info("뷰티 판정 시작 — 대상 {}명(재료 없음 스킵 {}), 배치 {}개", cards.size(), skipped, chunks.size());
        int total = chunks.size(), i = 0;
        for (List<BeautyJudge.ProfileCard> chunk : chunks) {
            if (stopFlag.isRequested(JobName.BEAUTY)) {
                log.info("beauty 중지 요청 — 잔여 배치 건너뛰고 조기 종료 ({}/{} 배치 처리)", i, total);
                break;
            }
            i++;
            List<BeautyJudge.Verdict> verdicts;
            try {
                verdicts = judge.judge(chunk);  // 트랜잭션 밖 — 최대 120초 CLI 호출 동안 커넥션 미점유
            } catch (ApifyException e) {
                failedBatches++;  // 해당 배치 계정은 beauty_class NULL 유지 — 다음 실행 재시도
                log.warn("뷰티 판정 배치 실패 ({}/{}, {}명): {}", i, total, chunk.size(), e.getMessage());
                continue;
            }
            int done = beauty + service + foreign + notBeauty;
            ChunkResult r = txTemplate.execute(
                    status -> applyVerdicts(verdicts, byUsername, captionCounts, done, cards.size()));
            beauty += r.beauty();
            service += r.service();
            foreign += r.foreign();
            notBeauty += r.notBeauty();
            log.info("뷰티 판정 배치 ({}/{}) 완료 — 누계 뷰티 {} / 시술·서비스 {} / 외국인 {} / 비뷰티 {}",
                    i, total, beauty, service, foreign, notBeauty);
        }
        return new Summary(beauty, service, foreign, notBeauty, skipped, failedBatches);
    }

    /**
     * 프로필 응답에 게시물이 없는 소스(HIKER_MOBILE·DATALIKERS)의 폴백 — 이미 수집된 릴스 페이지의
     * 실측 캡션을 판정 재료로 쓴다. 추가 크롤 없음(raw_media_page는 REELS 잡이 이미 채운 것).
     */
    private List<String> mediaCaptions(Long influencerId) {
        return rawMediaPages
                .findTopByInfluencerIdAndSourceOrderByCapturedAtDesc(influencerId, RawSource.HIKER_V2_CLIPS)
                .map(page -> MediaItemExtractor.captions(page.getPayload(), page.getSource()))
                .orElseGet(List::of);
    }

    /** 판정 재료 캡션 정책 적용 — 최근 CAPTION_COUNT개, 각 CAPTION_MAX_CHARS자까지. */
    private static List<String> trimCaptions(List<String> captions) {
        return captions.stream()
                .limit(CAPTION_COUNT)
                .map(BeautyJob::trimSurrogateSafe)
                .toList();
    }

    /**
     * 절단 경계가 이모지(서로게이트 쌍) 한가운데면 high surrogate 반쪽만 남아 깨진 문자열이 되고,
     * Anthropic API가 요청 JSON 전체를 400으로 거부한다(2026-07-21 운영 배치 9/10 실패 실측).
     * 경계가 쌍을 가르면 한 문자 앞에서 끊는다.
     */
    private static String trimSurrogateSafe(String c) {
        if (c.length() <= CAPTION_MAX_CHARS) return c;
        int end = CAPTION_MAX_CHARS;
        if (Character.isHighSurrogate(c.charAt(end - 1))) end--;
        return c.substring(0, end);
    }

    private record ChunkResult(int beauty, int service, int foreign, int notBeauty) {}

    /**
     * 판정 결과 적용(트랜잭션 안). targets 조회가 트랜잭션 밖(레포 자체 트랜잭션)에서 이뤄져 Influencer가
     * detached 상태다 — 세터만으로는 저장되지 않으므로 influencers.save(inf) 명시 호출이 필수다
     * (CollectJob이 방문 단위 트랜잭션 전환 때 겪은 회귀와 동일 — CollectJobIntegrationTest 참고).
     */
    private ChunkResult applyVerdicts(List<BeautyJudge.Verdict> verdicts, Map<String, Influencer> byUsername,
                                      Map<String, Integer> captionCounts, int done, int totalCards) {
        int beauty = 0, service = 0, foreign = 0, notBeauty = 0;
        for (BeautyJudge.Verdict v : verdicts) {
            Influencer inf = byUsername.get(v.username());
            if (inf == null) continue;  // 응답이 지어낸 username — 무시
            inf.classify(v.beautyClass(), Influencer.BEAUTY_SOURCE_CLAUDE, v.reason(), v.basis());
            inf.setBeautyJudgedAt(clock.instant());  // rejudge의 '오래된 판정 우선' 기준
            // 판정에 실제로 쓴 캡션 건수 — 0이면 나중에 캡션이 쌓였을 때 재판정 대상이 된다
            inf.setBeautyCaptionCount(captionCounts.getOrDefault(v.username(), 0).shortValue());
            influencers.save(inf);
            switch (v.beautyClass()) {
                case INFLUENCER, COMPANY -> beauty++;
                case BEAUTY_SERVICE -> service++;
                case FOREIGN_INFLUENCER -> foreign++;
                case NOT_BEAUTY -> notBeauty++;
            }
            done++;
            String label = switch (v.beautyClass()) {
                case INFLUENCER -> "뷰티(인플루언서)";
                case COMPANY -> "뷰티(회사)";
                case BEAUTY_SERVICE -> "뷰티(시술·서비스)";
                case FOREIGN_INFLUENCER -> "뷰티(외국인)";
                case NOT_BEAUTY -> "비뷰티";
            };
            log.info("뷰티 판정 ({}/{}) {} — {} ({})", done, totalCards, v.username(), label, v.reason());
        }
        return new ChunkResult(beauty, service, foreign, notBeauty);
    }
}
