package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SELF 베이스 + 폴백 컴포지트 — web_profile_info로 배치를 돌리고, IP 무관 HTTP 400
 * (비즈니스 카테고리 버그) 또는 연속 빈 응답(200 + user 없음 — IG가 일부 계정을 익명
 * API에서 숨기는 케이스)이 난 계정만 HikerAPI /v2/user/by/username으로 2차 조회해 병합한다.
 * 호출자가 ex.runId()로 raw를 저장하므로 crawl_run은 컴포지트 라벨로 1건만 만든다 —
 * 두 페처의 fetch()가 아니라 collect 로직을 직접 호출하는 이유. 혼합 배치의 아이템별
 * 실제 소스는 ProfileExtractor.detect로 구분한다.
 *
 * <p>빈 응답은 400과 달리 진짜 소멸 계정(비활성화·탈퇴 유예)과 구분이 안 된다 — 연속
 * 임계값에 도달했을 때만 유료 폴백을 쓰고, 폴백조차 빈 응답이면 카운터를 리셋해 기존
 * 재시도 경로로 복귀한다(소멸 계정의 유료 콜을 임계값 주기당 1회로 제한). 폴백이
 * 성공하면 카운터를 유지해 다음 빈 응답부터는 즉시 폴백한다(Hiker 수집 가능 확인됨).
 * 카운터는 인메모리라 재기동 시 초기화된다 — 임계값만큼의 방문 실패 후 폴백이 재개된다.
 */
@Component
public class SelfWithHikerFallbackProfileFetcher implements ProfileFetcher {

    static final String LABEL = "profile-self-hiker";
    /** 빈 응답이 이만큼 연속되면 Hiker 폴백 — 1회성 응답 누락에 유료 콜을 쓰지 않는 가드. */
    static final int EMPTY_STREAK_FALLBACK_THRESHOLD = 2;
    private static final Logger log = LoggerFactory.getLogger(SelfWithHikerFallbackProfileFetcher.class);

    private final SelfProfileFetcher self;
    private final HikerMobileProfileFetcher hiker;
    private final CrawlExecutor executor;
    /** 계정별 연속 빈 응답 횟수 — SELF 성공·폴백 실패(빈 응답)·계정 소멸 시 제거된다. */
    private final ConcurrentHashMap<String, Integer> emptyStreaks = new ConcurrentHashMap<>();

    public SelfWithHikerFallbackProfileFetcher(SelfProfileFetcher self, HikerMobileProfileFetcher hiker,
                                               CrawlExecutor executor) {
        this.self = self;
        this.hiker = hiker;
        this.executor = executor;
    }

    @Override
    public CrawlExecutor.Execution fetch(JobName job, List<String> usernames, TriggerType trigger) {
        return executor.execute(job, trigger, null, null, LABEL, () -> collect(usernames));
    }

    private ApifyResult collect(List<String> usernames) {
        List<String> badRequest = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        ApifyResult base = self.collect(usernames, badRequest, empty);
        resetStreaksForResolved(base);
        List<String> emptyFallback = new ArrayList<>();
        for (String u : empty) {
            int streak = emptyStreaks.merge(u, 1, Integer::sum);
            if (streak >= EMPTY_STREAK_FALLBACK_THRESHOLD) {
                emptyFallback.add(u);
            } else {
                log.info("빈 응답 연속 {}회 — 임계값({}) 미만, 폴백 유보: {}",
                        streak, EMPTY_STREAK_FALLBACK_THRESHOLD, u);
            }
        }
        if (badRequest.isEmpty() && emptyFallback.isEmpty()) return base;
        List<String> fallbackTargets = new ArrayList<>(badRequest);
        fallbackTargets.addAll(emptyFallback);
        if (!badRequest.isEmpty()) log.info("SELF 400 {}건 — Hiker 폴백: {}", badRequest.size(), badRequest);
        if (!emptyFallback.isEmpty()) {
            log.info("빈 응답 연속 임계값 도달 {}건 — Hiker 폴백: {}", emptyFallback.size(), emptyFallback);
        }
        ApifyResult fallback = hiker.collect(fallbackTargets);
        settleEmptyStreaks(emptyFallback, fallback);
        List<Map<String, Object>> items = new ArrayList<>(base.items());
        items.addAll(fallback.items());
        List<String> notFound = new ArrayList<>(base.notFound());
        notFound.addAll(fallback.notFound());
        return new ApifyResult(null, items, notFound);
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
     * 빈 응답 폴백의 후처리 — 성공 계정은 카운터를 유지해 다음 빈 응답부터 즉시 폴백하고
     * (Hiker 수집 가능 확인), 실패(폴백도 빈 응답)·404 계정은 카운터를 제거해 기존 재시도
     * 경로로 복귀시킨다(소멸 추정 계정의 반복 과금 방지).
     */
    private void settleEmptyStreaks(List<String> emptyFallback, ApifyResult fallback) {
        if (emptyFallback.isEmpty()) return;
        Set<String> recovered = new HashSet<>();
        for (Map<String, Object> item : fallback.items()) {
            String u = ProfileExtractor.username(item, RawSource.HIKER_MOBILE);
            if (u != null) recovered.add(u);
        }
        for (String u : emptyFallback) {
            if (recovered.contains(u)) continue;
            emptyStreaks.remove(u);
            if (!fallback.notFound().contains(u)) {
                log.info("빈 응답 폴백도 계정 확보 실패 — 카운터 리셋, 기존 재시도 경로로 복귀: {}", u);
            }
        }
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
