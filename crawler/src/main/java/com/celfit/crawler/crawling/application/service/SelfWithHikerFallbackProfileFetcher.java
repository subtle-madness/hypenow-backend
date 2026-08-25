package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.ProfileFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 *
 * <p>빈 응답 트랙은 <b>COLLECT·QUALIFY 잡 전용</b>이다 — 두 잡 모두 confirmedEmpty를 소비하는
 * 종결 장치가 있어야 열 수 있다(없으면 숨겨진 계정이 재선정마다 무한 재과금된다). CollectJob은
 * 30일 수명 정책, QualifyJob은 즉시 소프트 딜리트(DISCOVERED는 아직 리드일 뿐이라 보수적일
 * 이유가 없고, 재발굴되면 다시 들어온다)로 종결한다. 400 폴백은 잡 무관 공통.
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
        return executor.execute(job, trigger, null, null, LABEL, () -> collect(job, usernames));
    }

    private ApifyResult collect(JobName job, List<String> usernames) {
        List<String> badRequest = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        ApifyResult base = self.collect(usernames, badRequest, empty);
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
        if (badRequest.isEmpty() && emptyFallback.isEmpty()) return base;
        List<String> fallbackTargets = new ArrayList<>(badRequest);
        fallbackTargets.addAll(emptyFallback);
        if (!badRequest.isEmpty()) log.info("SELF 400 {}건 — Hiker 폴백: {}", badRequest.size(), badRequest);
        if (!emptyFallback.isEmpty()) {
            log.info("빈 응답 연속 임계값 도달 {}건 — Hiker 폴백: {}", emptyFallback.size(), emptyFallback);
        }
        List<String> hikerEmpty = new ArrayList<>();
        ApifyResult fallback = hiker.collect(fallbackTargets, hikerEmpty);
        List<String> confirmedEmpty = settleEmptyStreaks(emptyFallback, fallback, hikerEmpty);
        List<Map<String, Object>> items = new ArrayList<>(base.items());
        items.addAll(fallback.items());
        List<String> notFound = new ArrayList<>(base.notFound());
        notFound.addAll(fallback.notFound());
        return new ApifyResult(null, null, items, notFound, confirmedEmpty);
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
