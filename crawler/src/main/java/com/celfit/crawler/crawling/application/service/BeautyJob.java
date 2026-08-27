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
import java.util.HashSet;
import java.util.LinkedHashSet;
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
 * 판정 잡 — 두 카테고리 축(뷰티·F&B)을 1콜로 판정한다. QUALIFIED 중 미판정분의 최신 raw_profile
 * 텍스트 재료(이름·카테고리·bio)를 로컬 Claude에 배치로 넘겨 beauty_class·fnb_class와 각 파생
 * boolean을 저장한다. 인스타그램 API 호출 없음(비용 $0).
 * 선정은 세 경로다 — (1) 뷰티 미판정 신규, (2) F&B 백필(뷰티는 판정 완료·F&B만 미판정 — 스펙
 * 2026-08-23 §3), (3) rejudge=true일 때의 재판정. (2)는 F&B 축만 적용하고 뷰티 판정은
 * MANUAL 포함해 절대 덮지 않는다(fnbOnly 마스크).
 * rejudge는 판정 후 재료(raw_profile)가 갱신된 CLAUDE 비뷰티 판정분을 재판정한다 —
 * 뷰티 MANUAL(수동)은 선정에서 빠져 덮이지 않고, F&amp;B MANUAL은 선정 경로와 무관하게
 * 적용 시점 가드(fnb_source=MANUAL이면 F&amp;B 축 미적용)로 보존된다.
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

    /**
     * 캡션 재판정에 필요한 릴스 페이지 최소 아이템 수 — 아이템 1~2개짜리 페이지로 재판정을 돌리면
     * 근거가 캡션 0건 때와 별로 다르지 않아 LLM 호출만 낭비된다.
     */
    static final int REJUDGE_MIN_ITEMS = 3;

    public record Summary(int judgedBeauty, int judgedService, int judgedForeign, int judgedNotBeauty,
                          int fnbApplied, int fnbPositive, int skippedNoProfile, int failedBatches) {}

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
        // F&B 백필 — 뷰티 축은 판정 완료, F&B 축만 채운다(스펙 §3). 뷰티 판정(MANUAL 포함)은 덮지 않는다.
        // rejudge보다 앞선다 — 초기 백로그 소화가 이번 확장의 목적이고, rejudge는 백필 완료 후 자연 재개된다.
        Set<String> fnbOnly = new HashSet<>();
        if (targets.size() < limit) {
            List<Influencer> backfill = influencers.findFnbBackfillTargets(
                    InfluencerStatus.QUALIFIED, PageRequest.of(0, limit - targets.size()));
            backfill.forEach(i -> fnbOnly.add(i.getUsername()));
            targets.addAll(backfill);
        }
        if (rejudge && targets.size() < limit) {
            // 재료(raw_profile)가 판정 후 갱신된 비뷰티만 — 재료가 그대로면 같은 판정만 반복한다.
            // 쿨다운(beauty.rejudge-cooldown-days) 이내 판정분은 제외 — F&B 수집 계정의 매일 재선정 차단.
            // 오래된 판정 우선(쿼리 정렬) — 실패 배치가 옛 판정 시각으로 남아 먼저 재시도된다
            java.time.Instant cooldownBefore = clock.instant()
                    .minus(java.time.Duration.ofDays(settings.beautyRejudgeCooldownDays()));
            targets.addAll(influencers.findRejudgeTargets(
                    InfluencerStatus.QUALIFIED, Influencer.BEAUTY_SOURCE_CLAUDE,
                    cooldownBefore, PageRequest.of(0, limit - targets.size())));
        }
        if (rejudge && targets.size() < limit) {
            // 캡션 0건으로 판정된 뒤 릴스가 쌓인 계정 — 뷰티 판정분도 포함해 실측으로 되돌린다
            targets.addAll(influencers.findCaptionRejudgeTargets(
                    InfluencerStatus.QUALIFIED.name(), Influencer.BEAUTY_SOURCE_CLAUDE,
                    REJUDGE_MIN_ITEMS, PageRequest.of(0, limit - targets.size())));
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
        int fnbApplied = 0, fnbPositive = 0, done = 0;
        List<List<BeautyJudge.ProfileCard>> chunks = ActorInputs.chunk(cards, JUDGE_CHUNK);
        // 백필 선정분 중 실제로 카드가 만들어진 수 — fnbOnly 원본 크기를 쓰면 재료 없어 스킵된 건까지
        // 세어 "대상 N명 그중 백필 M"의 M이 N을 넘을 수 있다(백필 백로그 소진 속도를 오독하게 된다).
        long backfillCards = fnbOnly.stream().filter(byUsername::containsKey).count();
        log.info("뷰티 판정 시작 — 대상 {}명(재료 없음 스킵 {}, 그중 F&B 백필 선정 {}), 배치 {}개",
                cards.size(), skipped, backfillCards, chunks.size());
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
            logResponseGaps(chunk, verdicts, fnbOnly);
            int doneBefore = done;
            ChunkResult r = txTemplate.execute(status ->
                    applyVerdicts(verdicts, byUsername, captionCounts, fnbOnly, doneBefore, cards.size()));
            beauty += r.beauty();
            service += r.service();
            foreign += r.foreign();
            notBeauty += r.notBeauty();
            fnbApplied += r.fnbApplied();
            fnbPositive += r.fnbPositive();
            done += r.applied();
            log.info("뷰티 판정 배치 ({}/{}) 완료 — 누계 뷰티 {} / 시술·서비스 {} / 외국인 {} / 비뷰티 {}"
                            + " / F&B 적용 {} (인플루언서·회사 {})",
                    i, total, beauty, service, foreign, notBeauty, fnbApplied, fnbPositive);
        }
        return new Summary(beauty, service, foreign, notBeauty, fnbApplied, fnbPositive,
                skipped, failedBatches);
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

    /** applied는 이 배치에서 실제로 저장한 계정 수 — 계정별 로그의 진행 카운터 기준(F&B만 적용한 건 포함). */
    private record ChunkResult(int beauty, int service, int foreign, int notBeauty,
                               int fnbApplied, int fnbPositive, int applied) {}

    /**
     * 응답 누락·중복·축 부분 무응답 관측 — 모델이 요청한 계정 일부를 빼먹거나 한 축만 무효값으로 내도
     * 예외가 아니라서(나머지 판정을 버릴 이유가 없다) 조용히 지나가던 것을 로그로 드러낸다. 누락분·
     * 무응답 축은 미판정으로 남아 다음 실행에 재시도되므로 데이터 유실은 아니지만, 빈도가 높아지면
     * 프롬프트·청크 크기를 의심해야 한다. 백필(fnbOnly)은 뷰티 축을 애초에 안 쓰므로 결손이 아니다.
     */
    private static void logResponseGaps(List<BeautyJudge.ProfileCard> chunk,
                                        List<BeautyJudge.Verdict> verdicts, Set<String> fnbOnly) {
        Set<String> returned = new LinkedHashSet<>();
        List<String> dups = new ArrayList<>();
        int beautyGaps = 0, fnbGaps = 0;
        for (BeautyJudge.Verdict v : verdicts) {
            if (!returned.add(v.username())) {
                dups.add(v.username());
                continue;  // 중복분은 어차피 버려지므로 축 결손으로도 세지 않는다
            }
            if (v.beautyClass() == null && !fnbOnly.contains(v.username())) beautyGaps++;
            if (v.fnbClass() == null) fnbGaps++;
        }
        List<String> missing = chunk.stream()
                .map(BeautyJudge.ProfileCard::username)
                .filter(u -> !returned.contains(u))
                .toList();
        if (!missing.isEmpty()) {
            log.warn("뷰티 판정 응답 누락 {}건 — 미판정 유지, 다음 실행 재시도: {}", missing.size(), missing);
        }
        if (!dups.isEmpty()) {
            log.warn("뷰티 판정 응답 중복 {}건 — 첫 값이 적용됨: {}", dups.size(), dups);
        }
        if (beautyGaps > 0 || fnbGaps > 0) {
            log.warn("판정 응답 축 부분 무응답 — 뷰티축 {}건 / F&B축 {}건 (해당 축만 미판정 유지, 다음 실행 재시도)",
                    beautyGaps, fnbGaps);
        }
    }

    /**
     * 판정 결과 적용(트랜잭션 안). targets 조회가 트랜잭션 밖(레포 자체 트랜잭션)에서 이뤄져 Influencer가
     * detached 상태다 — 세터만으로는 저장되지 않으므로 influencers.save(inf) 명시 호출이 필수다
     * (CollectJob이 방문 단위 트랜잭션 전환 때 겪은 회귀와 동일 — CollectJobIntegrationTest 참고).
     */
    private ChunkResult applyVerdicts(List<BeautyJudge.Verdict> verdicts, Map<String, Influencer> byUsername,
                                      Map<String, Integer> captionCounts, Set<String> fnbOnly,
                                      int done, int totalCards) {
        int beauty = 0, service = 0, foreign = 0, notBeauty = 0, fnbApplied = 0, fnbPositive = 0;
        int startDone = done;
        Set<String> applied = new HashSet<>();  // 중복 응답 방어 — 첫 값만 채택(logResponseGaps가 경고)
        for (BeautyJudge.Verdict v : verdicts) {
            if (!applied.add(v.username())) continue;  // 같은 username 재등장 — 카운터·save·로그 모두 건너뜀
            Influencer inf = byUsername.get(v.username());
            if (inf == null) continue;  // 응답이 지어낸 username — 무시
            // 판정에 실제로 쓴 캡션 건수 — 0이면 나중에 캡션이 쌓였을 때 재판정 대상이 된다
            short capCount = captionCounts.getOrDefault(v.username(), 0).shortValue();
            // F&B 백필로 선정된 계정은 뷰티 축을 절대 덮지 않는다 — 모델이 뷰티 판정을 같이 내도 버린다
            // (MANUAL 수동 교정분도 백필 대상이라, 여기서 막지 않으면 수동 판정이 조용히 날아간다).
            boolean applyBeauty = !fnbOnly.contains(v.username()) && v.beautyClass() != null;
            // F&B 축의 MANUAL(수동 교정)은 적용 시점에 막는다 — fnbOnly 마스크는 뷰티 축만 보호하므로,
            // rejudge·신규 경로로 같은 계정이 다시 잡히면 수동 F&B 판정이 CLAUDE로 조용히 덮인다.
            boolean applyFnb = v.fnbClass() != null
                    && !Influencer.BEAUTY_SOURCE_MANUAL.equals(inf.getFnbSource());
            // 적용할 축이 하나도 없으면(마스크·MANUAL 가드로 둘 다 버렸거나 양축 무응답) 저장·카운터·
            // 계정별 로그를 모두 건너뛴다 — 바뀐 게 없는데 진행 카운터가 오르면 배치 진척을 오독한다.
            if (!applyBeauty && !applyFnb) continue;
            // 축별 class는 모델 응답이 무효·누락이면 null — 그 축만 미판정으로 남기고 다른 축은 적용한다
            String beautyLabel = fnbOnly.contains(v.username()) ? "뷰티 판정 보존" : "뷰티축 무응답";
            if (applyBeauty) {
                inf.classify(v.beautyClass(), Influencer.BEAUTY_SOURCE_CLAUDE, v.reason(), v.basis());
                inf.setBeautyJudgedAt(clock.instant());  // rejudge의 '오래된 판정 우선' 기준
                inf.setBeautyCaptionCount(capCount);
                switch (v.beautyClass()) {
                    case INFLUENCER, COMPANY -> beauty++;
                    case BEAUTY_SERVICE -> service++;
                    case FOREIGN_INFLUENCER -> foreign++;
                    case NOT_BEAUTY -> notBeauty++;
                }
                beautyLabel = switch (v.beautyClass()) {
                    case INFLUENCER -> "뷰티(인플루언서)";
                    case COMPANY -> "뷰티(회사)";
                    case BEAUTY_SERVICE -> "뷰티(시술·서비스)";
                    case FOREIGN_INFLUENCER -> "뷰티(외국인)";
                    case NOT_BEAUTY -> "비뷰티";
                };
            }
            if (applyFnb) {
                inf.classifyFnb(v.fnbClass(), Influencer.BEAUTY_SOURCE_CLAUDE, v.fnbReason(), v.fnbBasis());
                inf.setFnbJudgedAt(clock.instant());
                inf.setFnbCaptionCount(capCount);
                fnbApplied++;
                if (v.fnbClass().inCategory()) fnbPositive++;
            }
            String fnbLabel = !applyFnb ? "" : " / " + switch (v.fnbClass()) {
                case INFLUENCER -> "F&B(인플루언서)";
                case COMPANY -> "F&B(회사)";
                case SERVICE -> "F&B(매장·서비스)";
                case FOREIGN_INFLUENCER -> "F&B(외국인)";
                case NONE -> "비F&B";
            };
            influencers.save(inf);
            done++;
            // 사유는 실제로 적용한 축의 것 — 뷰티를 안 쓴 건에 뷰티 사유가 찍히면 덮인 것으로 오독된다
            String reason = applyBeauty ? v.reason() : v.fnbReason();
            log.info("뷰티 판정 ({}/{}) {} — {}{} ({})",
                    done, totalCards, v.username(), beautyLabel, fnbLabel, reason);
        }
        return new ChunkResult(beauty, service, foreign, notBeauty, fnbApplied, fnbPositive,
                done - startDone);
    }
}
