package com.celfit.crawler.settings.application.service;

import com.celfit.crawler.common.config.BeautyProperties;
import com.celfit.crawler.common.config.CollectProperties;
import com.celfit.crawler.common.config.DiscoverProperties;
import com.celfit.crawler.common.config.QualifyProperties;
import com.celfit.crawler.common.config.ReelsProperties;
import com.celfit.crawler.common.config.SimilarProperties;
import com.celfit.crawler.settings.domain.AppSetting;
import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 수집 튜닝 값의 런타임 오버라이드. DB(app_setting)에 값이 있으면 그 값, 없으면 yml 기본값을
 * 사용한다. yml은 기본값 역할로만 남는다.
 */
@Service
public class SettingsService {

    public record SettingView(String key, int effective, int defaultValue, boolean overridden,
                              String description) {}

    static final String RESULTS_LIMIT = "discover.results-limit";
    static final String QUALIFY_BATCH_LIMIT = "qualify.batch-limit";
    static final String QUALIFY_MIN_FOLLOWERS = "qualify.min-followers";
    static final String QUALIFY_MAX_FOLLOWERS = "qualify.max-followers";
    static final String COLLECT_BATCH_LIMIT = "collect.batch-limit";
    static final String COLLECT_COMMENTS_PER_POST = "collect.comments-per-post";
    static final String COLLECT_MAX_ATTEMPTS = "collect.max-attempts";
    static final String COLLECT_REVISIT_INTERVAL_DAYS = "collect.revisit-interval-days";
    static final String SIMILAR_BATCH_LIMIT = "similar.batch-limit";
    static final String BEAUTY_BATCH_LIMIT = "beauty.batch-limit";
    static final String REELS_BATCH_LIMIT = "reels.batch-limit";
    static final String REELS_ACTOR_RESULTS_LIMIT = "reels.actor-results-limit";

    /** F&B 파이프라인 게이트 키 — 수집(collect·reels)·유사발굴 시드·비용 추정의 F&B 편입 여부. */
    static final String FNB_PIPELINE_ENABLED = "fnb.pipeline-enabled";

    // 댓글 관련 키(comments-per-post·max-attempts)는 댓글 수집이 꺼지면서(yml comments-enabled)
    // UI 목록에서 제외 — 로직·기본값은 유지되므로 재활성화 시 다시 넣으면 된다.
    private static final List<String> KEYS = List.of(
            RESULTS_LIMIT, QUALIFY_BATCH_LIMIT, QUALIFY_MIN_FOLLOWERS, QUALIFY_MAX_FOLLOWERS,
            COLLECT_BATCH_LIMIT, COLLECT_REVISIT_INTERVAL_DAYS, SIMILAR_BATCH_LIMIT,
            BEAUTY_BATCH_LIMIT, REELS_BATCH_LIMIT, REELS_ACTOR_RESULTS_LIMIT);

    private static final java.util.Map<String, String> DESCRIPTIONS = java.util.Map.of(
            RESULTS_LIMIT, "discover: 키워드당 발굴할 게시물 수 상한 (해시태그 페이지 반복량 결정)",
            QUALIFY_BATCH_LIMIT, "qualify: 판정 1회당 처리할 인플루언서 수 상한 (프로필 호출량 제어)",
            QUALIFY_MIN_FOLLOWERS, "qualify: 판정 통과 팔로워 하한 — 미만이면 EXCLUDED (전역)",
            QUALIFY_MAX_FOLLOWERS, "qualify: 판정 통과 팔로워 상한 — 초과면 EXCLUDED (전역)",
            COLLECT_BATCH_LIMIT, "collect: 실행 1회당 방문할 인플루언서 수",
            COLLECT_REVISIT_INTERVAL_DAYS, "collect: 재방문 주기 (일) — 달력 기준. 1이면 오늘(KST) 아직 방문 안 한 계정이 대상, 자정에 전원 리셋",
            SIMILAR_BATCH_LIMIT, "similar: 실행 1회당 유사 계정을 수확할 시드 수 (Hiker 호출량 제어)",
            BEAUTY_BATCH_LIMIT, "beauty: 판정 1회당 처리할 계정 수 상한 (실행 시간 제어 — 초과분은 다음 실행)",
            REELS_BATCH_LIMIT, "reels: 실행 1회당 릴스를 수확할 계정 수 (Hiker 호출량 제어 — 계정당 1요청)",
            REELS_ACTOR_RESULTS_LIMIT, "reels: ACTOR 소스일 때 계정당 수확할 릴스 수 (Apify 결과 건수 과금)");

    private final AppSettingRepository settings;
    private final DiscoverProperties discoverProps;
    private final QualifyProperties qualifyProps;
    private final CollectProperties collectProps;
    private final SimilarProperties similarProps;
    private final BeautyProperties beautyProps;
    private final ReelsProperties reelsProps;

    public SettingsService(AppSettingRepository settings, DiscoverProperties discoverProps,
                           QualifyProperties qualifyProps, CollectProperties collectProps,
                           SimilarProperties similarProps, BeautyProperties beautyProps,
                           ReelsProperties reelsProps) {
        this.settings = settings;
        this.discoverProps = discoverProps;
        this.qualifyProps = qualifyProps;
        this.collectProps = collectProps;
        this.similarProps = similarProps;
        this.beautyProps = beautyProps;
        this.reelsProps = reelsProps;
    }

    @Transactional(readOnly = true)
    public int resultsLimit() {
        return effective(RESULTS_LIMIT);
    }

    @Transactional(readOnly = true)
    public int qualifyBatchLimit() {
        return effective(QUALIFY_BATCH_LIMIT);
    }

    @Transactional(readOnly = true)
    public int qualifyMinFollowers() {
        return effective(QUALIFY_MIN_FOLLOWERS);
    }

    @Transactional(readOnly = true)
    public int qualifyMaxFollowers() {
        return effective(QUALIFY_MAX_FOLLOWERS);
    }

    @Transactional(readOnly = true)
    public int collectBatchLimit() {
        return effective(COLLECT_BATCH_LIMIT);
    }

    @Transactional(readOnly = true)
    public int commentsPerPost() {
        return effective(COLLECT_COMMENTS_PER_POST);
    }

    @Transactional(readOnly = true)
    public int maxAttempts() {
        return effective(COLLECT_MAX_ATTEMPTS);
    }

    @Transactional(readOnly = true)
    public int revisitIntervalDays() {
        return effective(COLLECT_REVISIT_INTERVAL_DAYS);
    }

    @Transactional(readOnly = true)
    public int similarBatchLimit() {
        return effective(SIMILAR_BATCH_LIMIT);
    }

    @Transactional(readOnly = true)
    public int beautyBatchLimit() {
        return effective(BEAUTY_BATCH_LIMIT);
    }

    @Transactional(readOnly = true)
    public int reelsBatchLimit() {
        return effective(REELS_BATCH_LIMIT);
    }

    @Transactional(readOnly = true)
    public int reelsActorResultsLimit() {
        return effective(REELS_ACTOR_RESULTS_LIMIT);
    }

    /**
     * F&B 판정 통과 계정의 수집·시드 편입 여부(기본 false — 스펙 2026-08-23 §4).
     * 숫자 설정(KEYS·UI 목록)과 달리 boolean 런타임 토글 — on은 운영 수동 UPDATE.
     */
    @Transactional(readOnly = true)
    public boolean fnbPipelineEnabled() {
        return settings.findById(FNB_PIPELINE_ENABLED)
                .map(s -> Boolean.parseBoolean(s.getValue()))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<SettingView> list() {
        return KEYS.stream().map(this::toView).toList();
    }

    /** value == null이면 오버라이드를 지워 기본값으로 복귀. 모르는 키·범위 위반 값은 400. */
    @Transactional
    public SettingView update(String key, Integer value) {
        defaultValue(key);  // 모르는 키면 여기서 400
        if (value == null) {
            if (settings.existsById(key)) settings.deleteById(key);
        } else {
            validate(key, value);
            settings.save(new AppSetting(key, String.valueOf(value)));
        }
        return toView(key);
    }

    private SettingView toView(String key) {
        int def = defaultValue(key);
        String desc = DESCRIPTIONS.getOrDefault(key, "");
        return settings.findById(key)
                .map(s -> new SettingView(key, Integer.parseInt(s.getValue()), def, true, desc))
                .orElseGet(() -> new SettingView(key, def, def, false, desc));
    }

    private int effective(String key) {
        return settings.findById(key)
                .map(s -> Integer.parseInt(s.getValue()))
                .orElseGet(() -> defaultValue(key));
    }

    private int defaultValue(String key) {
        return switch (key) {
            case RESULTS_LIMIT -> discoverProps.resultsLimit();
            case QUALIFY_BATCH_LIMIT -> qualifyProps.batchLimit();
            case QUALIFY_MIN_FOLLOWERS -> qualifyProps.minFollowers();
            case QUALIFY_MAX_FOLLOWERS -> qualifyProps.maxFollowers();
            case COLLECT_BATCH_LIMIT -> collectProps.batchLimit();
            case COLLECT_COMMENTS_PER_POST -> collectProps.commentsPerPost();
            case COLLECT_MAX_ATTEMPTS -> collectProps.maxAttempts();
            case COLLECT_REVISIT_INTERVAL_DAYS -> collectProps.revisitIntervalDays();
            case SIMILAR_BATCH_LIMIT -> similarProps.batchLimit();
            case BEAUTY_BATCH_LIMIT -> beautyProps.batchLimit();
            case REELS_BATCH_LIMIT -> reelsProps.batchLimit();
            case REELS_ACTOR_RESULTS_LIMIT -> reelsProps.actorResultsLimit();
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "알 수 없는 설정 키: " + key);
        };
    }

    private void validate(String key, int value) {
        int min = 1;
        if (value < min) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "잘못된 값: " + key + "=" + value + " (최소 " + min + ")");
        }
    }
}
