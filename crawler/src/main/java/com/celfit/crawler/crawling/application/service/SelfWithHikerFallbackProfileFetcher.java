package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SELF 베이스 + 폴백 컴포지트 — web_profile_info로 배치를 돌리고, IP 무관 HTTP 400
 * (비즈니스 카테고리 버그) · 블록/전송오류 소진(429·401·403·타임아웃 등, BLOCK_MAX_ATTEMPTS회
 * 재시도 후) · 연속 빈 응답(200 + user 없음 — IG가 일부 계정을 익명 API에서 숨기는 케이스)이
 * 난 계정만 HikerAPI /v2/user/by/username으로 2차 조회해 병합한다. 호출자가 ex.runId()로
 * raw를 저장하므로 crawl_run은 컴포지트 라벨로 1건만 만든다 — 두 페처의 fetch()가 아니라
 * collect 로직을 직접 호출하는 이유. 혼합 배치의 아이템별 실제 소스는 ProfileExtractor.detect로
 * 구분한다.
 *
 * <p>블록/전송오류 폴백은 <b>잡 무관 공통</b>이며 연속 게이트가 없다 — SelfProfileFetcher의
 * BLOCK_MAX_ATTEMPTS 재시도 자체가 게이트다. 빈 응답은 400과 달리 진짜 소멸 계정(비활성화·
 * 탈퇴 유예)과 구분이 안 된다 — 연속 임계값에 도달했을 때만 유료 폴백을 쓰고, 폴백조차 빈
 * 응답이면 카운터를 리셋해 기존 재시도 경로로 복귀한다(소멸 계정의 유료 콜을 임계값 주기당
 * 1회로 제한). 폴백이 성공하면 카운터를 유지해 다음 빈 응답부터는 즉시 폴백한다(Hiker 수집
 * 가능 확인됨). 카운터는 인메모리라 재기동 시 초기화된다 — 임계값만큼의 방문 실패 후 폴백이
 * 재개된다.
 *
 * <p>빈 응답 트랙은 <b>COLLECT·QUALIFY 잡 전용</b>이다 — 두 잡 모두 confirmedEmpty를 소비하는
 * 종결 장치가 있어야 열 수 있다(없으면 숨겨진 계정이 재선정마다 무한 재과금된다). CollectJob은
 * 30일 수명 정책, QualifyJob은 즉시 소프트 딜리트(DISCOVERED는 아직 리드일 뿐이라 보수적일
 * 이유가 없고, 재발굴되면 다시 들어온다)로 종결한다.
 *
 * <p><b>프로세스 수준 헬스 게이트</b>(V3, 09-05~) — SELF 결과가 블록/전송오류로 연속
 * SELF_DEGRADE_STREAK회(호출 1회 = 그 배치 전원이 블록)면 "강등"되어, 이후 호출은 SELF를
 * 전혀 시도하지 않고 배치 전량을 즉시 Hiker로 돌린다(2026-09-02 IG의 로그아웃
 * web_profile_info 전면 401 차단 사고 재발 방지 — self가 살아나지 않으면 Hiker가 무조건
 * 대신 돈다). SELF_DEGRADE_COOLDOWN 경과 후 half-open: 다음 1회 호출만 SELF를 다시
 * 시도(probe)해 성공(블록 아닌 결과가 하나라도 있으면)이면 복귀, 다시 전량 블록이면 강등을
 * 연장한다. 이 게이트는 싱글턴 빈 인스턴스 전체(잡 무관)가 공유한다 — 계정별이 아니라
 * "SELF 자체가 죽었는지"를 판정하는 것이기 때문이다.
 */
@Component
public class SelfWithHikerFallbackProfileFetcher implements ProfileFetcher {

    static final String LABEL = "profile-self-hiker";
    /** 빈 응답이 이만큼 연속되면 Hiker 폴백 — 1회성 응답 누락에 유료 콜을 쓰지 않는 가드. */
    static final int EMPTY_STREAK_FALLBACK_THRESHOLD = 2;
    /** SELF가 이만큼 연속(호출 단위) 전량 블록되면 강등 — Hiker 직행으로 전환. */
    static final int SELF_DEGRADE_STREAK = 5;
    /** 강등 후 SELF를 다시 프로브하기까지의 쿨다운. */
    static final Duration SELF_DEGRADE_COOLDOWN = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(SelfWithHikerFallbackProfileFetcher.class);
    private static final String METRIC = "crawler.profile.fetch";

    private final SelfProfileFetcher self;
    private final HikerMobileProfileFetcher hiker;
    private final CrawlExecutor executor;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    /** 계정별 연속 빈 응답 횟수 — SELF 성공·폴백 실패(빈 응답)·계정 소멸 시 제거된다. */
    private final ConcurrentHashMap<String, Integer> emptyStreaks = new ConcurrentHashMap<>();
    /** SELF 호출(배치) 단위 연속 전량-블록 카운터 — 강등 판단 재료. */
    private final AtomicInteger selfBlockStreak = new AtomicInteger();
    /** null이면 정상, 값이 있으면 강등 시각(연장 시 갱신) — half-open 판단 기준. */
    private final AtomicReference<Instant> degradedAt = new AtomicReference<>();
    /** 쿨다운 경과 후 프로브 1회만 허용하는 게이트 — 동시 호출 중 하나만 SELF를 시도. */
    private final AtomicBoolean probing = new AtomicBoolean(false);

    @Autowired
    public SelfWithHikerFallbackProfileFetcher(SelfProfileFetcher self, HikerMobileProfileFetcher hiker,
                                               CrawlExecutor executor, MeterRegistry meterRegistry) {
        this(self, hiker, executor, meterRegistry, Clock.systemUTC());
    }

    /** 테스트용 — Clock 주입으로 쿨다운·half-open 시나리오를 결정적으로 재현한다. */
    SelfWithHikerFallbackProfileFetcher(SelfProfileFetcher self, HikerMobileProfileFetcher hiker,
                                        CrawlExecutor executor, MeterRegistry meterRegistry, Clock clock) {
        this.self = self;
        this.hiker = hiker;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    public CrawlExecutor.Execution fetch(JobName job, List<String> usernames, TriggerType trigger) {
        return executor.execute(job, trigger, null, null, LABEL, () -> collect(job, usernames));
    }

    private ApifyResult collect(JobName job, List<String> usernames) {
        List<String> badRequest = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        ApifyResult base;
        if (usernames.isEmpty()) {
            base = new ApifyResult(null, List.of(), List.of());
        } else if (tryEnterSelf()) {
            base = self.collect(usernames, badRequest, empty, blocked);
            recordSelfOutcome(usernames.size(), blocked.size());
            meterSelf(job, base.items().size(), badRequest.size(), empty.size(), base.notFound().size(),
                    blocked.size());
        } else {
            // 강등 상태(쿨다운 미경과) — SELF를 전혀 두드리지 않고 배치 전량을 즉시 Hiker로.
            base = new ApifyResult(null, List.of(), List.of());
            blocked = new ArrayList<>(usernames);
            count(job, "self", "degraded_skip", usernames.size());
        }
        List<String> emptyFallback = new ArrayList<>();
        // 빈 응답 트랙은 종결 장치가 있는 잡만 — 클래스 주석 참조
        if (job == JobName.COLLECT || job == JobName.QUALIFY) {
            resetStreaksForResolved(base);
            for (String u : empty) {
                int streak = emptyStreaks.merge(u, 1, Integer::sum);
                if (streak >= EMPTY_STREAK_FALLBACK_THRESHOLD) {
                    emptyFallback.add(u);
                } else {
                    log.info("빈 응답 연속 {}회 — 임계값({}) 미만, 폴백 유보: {}",
                            streak, EMPTY_STREAK_FALLBACK_THRESHOLD, u);
                }
            }
        }
        // 블록/전송오류 폴백은 잡 무관 공통 — SelfProfileFetcher의 재시도 소진이 곧 게이트다.
        if (badRequest.isEmpty() && emptyFallback.isEmpty() && blocked.isEmpty()) return base;
        List<String> fallbackTargets = new ArrayList<>(badRequest);
        fallbackTargets.addAll(emptyFallback);
        fallbackTargets.addAll(blocked);
        if (!badRequest.isEmpty()) log.info("SELF 400 {}건 — Hiker 폴백: {}", badRequest.size(), badRequest);
        if (!emptyFallback.isEmpty()) {
            log.info("빈 응답 연속 임계값 도달 {}건 — Hiker 폴백: {}", emptyFallback.size(), emptyFallback);
        }
        if (!blocked.isEmpty()) log.info("SELF 블록/전송오류 {}건 — Hiker 폴백: {}", blocked.size(), blocked);
        List<String> hikerEmpty = new ArrayList<>();
        ApifyResult fallback = hiker.collect(fallbackTargets, hikerEmpty);
        List<String> confirmedEmpty = settleEmptyStreaks(emptyFallback, fallback, hikerEmpty);
        meterFallback(job, fallbackTargets.size(), fallback.items().size(), fallback.notFound().size(),
                hikerEmpty.size());
        List<Map<String, Object>> items = new ArrayList<>(base.items());
        items.addAll(fallback.items());
        List<String> notFound = new ArrayList<>(base.notFound());
        notFound.addAll(fallback.notFound());
        return new ApifyResult(null, null, items, notFound, confirmedEmpty);
    }

    /**
     * 강등 상태 판단 — 정상이면 항상 true. 강등 중이면 쿨다운 미경과 시 false(SELF 스킵),
     * 경과 시 단 하나의 호출만 CAS로 프로브 자격을 얻어 true를 반환한다(동시 호출 다수가
     * 쿨다운 경과 순간에 몰려도 SELF는 1회만 두드린다).
     */
    private boolean tryEnterSelf() {
        Instant since = degradedAt.get();
        if (since == null) return true;
        if (Duration.between(since, clock.instant()).compareTo(SELF_DEGRADE_COOLDOWN) < 0) {
            return false;
        }
        return probing.compareAndSet(false, true);
    }

    /**
     * SELF 호출(배치) 1회의 결과를 헬스 게이트에 반영한다. 배치 전원이 블록이면 연속
     * 카운터를 올리고, 강등 중이었다면(half-open 프로브) 실패로 보아 강등을 연장한다.
     * 하나라도 비블록 결과가 있으면 카운터를 리셋하고, 강등 중이었다면 복귀시킨다.
     */
    private void recordSelfOutcome(int batchSize, int blockedCount) {
        boolean allBlocked = batchSize > 0 && blockedCount == batchSize;
        if (allBlocked) {
            int streak = selfBlockStreak.incrementAndGet();
            boolean wasDegraded = degradedAt.get() != null;
            if (wasDegraded) {
                // half-open 프로브도 블록 — 강등 연장
                degradedAt.set(clock.instant());
                probing.set(false);
            } else if (streak >= SELF_DEGRADE_STREAK) {
                degradedAt.set(clock.instant());
                log.warn("SELF 연속 전량 블록 {}회 — Hiker 직행으로 강등(쿨다운 {}분 후 프로브)",
                        streak, SELF_DEGRADE_COOLDOWN.toMinutes());
            }
        } else {
            selfBlockStreak.set(0);
            boolean wasDegraded = degradedAt.getAndSet(null) != null;
            probing.set(false);
            if (wasDegraded) {
                log.info("SELF 프로브 성공 — 정상 복귀");
            }
        }
    }

    private void meterSelf(JobName job, int ok, int badRequest, int empty, int notFound, int blockedCount) {
        count(job, "self", "ok", ok);
        count(job, "self", "bad_request", badRequest);
        count(job, "self", "empty", empty);
        count(job, "self", "not_found", notFound);
        count(job, "self", "blocked", blockedCount);
    }

    private void meterFallback(JobName job, int targets, int ok, int notFound, int empty) {
        count(job, "hiker", "fallback_ok", ok);
        count(job, "hiker", "fallback_not_found", notFound);
        count(job, "hiker", "fallback_empty", empty);
        int failed = targets - ok - notFound - empty;
        if (failed > 0) count(job, "hiker", "fallback_failed", failed);
    }

    private void count(JobName job, String source, String outcome, int n) {
        if (n <= 0) return;
        meterRegistry.counter(METRIC, "job", job.name().toLowerCase(), "source", source, "outcome", outcome)
                .increment(n);
    }

    /** SELF가 계정을 확보했거나 404(소멸)로 종결한 계정의 빈 응답 카운터 제거. */
    private void resetStreaksForResolved(ApifyResult base) {
        if (emptyStreaks.isEmpty()) return;
        for (Map<String, Object> item : base.items()) {
            String u = ProfileExtractor.username(item, RawSource.SELF_GQL);
            if (u != null) emptyStreaks.remove(u);
        }
        base.notFound().forEach(emptyStreaks::remove);
    }

    /**
     * 빈 응답 폴백의 후처리 — Hiker가 <b>응답으로 확인한</b> 빈 응답(hikerEmpty)만 카운터를
     * 제거하고 confirmedEmpty로 보고한다(호출자의 30일 수명 정책 재료). 404는 카운터만
     * 제거(소프트 삭제 경로로 종결). 그 외 — 회수 성공은 카운터 유지(다음 빈 응답부터 즉시
     * 폴백), 요청 실패(5xx·타임아웃: 어느 리스트에도 없음)도 카운터 유지로 다음 방문에서
     * 폴백을 재시도한다 — 인프라 오류를 계정 소멸 확인으로 오판하지 않는다.
     */
    private List<String> settleEmptyStreaks(List<String> emptyFallback, ApifyResult fallback,
                                            List<String> hikerEmpty) {
        if (emptyFallback.isEmpty()) return List.of();
        List<String> confirmedEmpty = new ArrayList<>();
        for (String u : emptyFallback) {
            if (hikerEmpty.contains(u)) {
                emptyStreaks.remove(u);
                confirmedEmpty.add(u);
                log.info("빈 응답 폴백도 빈 응답 확인 — 카운터 리셋, 기존 재시도 경로로 복귀: {}", u);
            } else if (fallback.notFound().contains(u)) {
                emptyStreaks.remove(u);
            }
        }
        return confirmedEmpty;
    }

    @Override
    public ProfileSource source() {
        return ProfileSource.SELF_HIKER_FALLBACK;
    }

    /** 혼합 배치의 기본 소스 — 아이템별 실제 소스는 ProfileExtractor.detect로 구분한다. */
    @Override
    public RawSource rawSource() {
        return RawSource.SELF_GQL;
    }
}
