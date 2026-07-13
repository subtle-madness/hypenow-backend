package com.celfit.crawler.settings.application.service;

import com.celfit.crawler.common.config.CollectProperties;
import com.celfit.crawler.common.config.DiscoverProperties;
import com.celfit.crawler.common.config.QualifyProperties;
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

    public record SettingView(String key, int effective, int defaultValue, boolean overridden) {}

    static final String RESULTS_LIMIT = "discover.results-limit";
    static final String QUALIFY_BATCH_LIMIT = "qualify.batch-limit";
    static final String QUALIFY_MIN_FOLLOWERS = "qualify.min-followers";
    static final String QUALIFY_MAX_FOLLOWERS = "qualify.max-followers";
    static final String COLLECT_BACKFILL_MONTHS = "collect.backfill-months";
    static final String COLLECT_TRACK_WINDOW_DAYS = "collect.track-window-days";
    static final String COLLECT_BATCH_LIMIT = "collect.batch-limit";
    static final String COLLECT_COMMENTS_PER_POST = "collect.comments-per-post";
    static final String COLLECT_MAX_ATTEMPTS = "collect.max-attempts";

    private static final List<String> KEYS = List.of(
            RESULTS_LIMIT, QUALIFY_BATCH_LIMIT, QUALIFY_MIN_FOLLOWERS, QUALIFY_MAX_FOLLOWERS,
            COLLECT_BACKFILL_MONTHS, COLLECT_TRACK_WINDOW_DAYS, COLLECT_BATCH_LIMIT,
            COLLECT_COMMENTS_PER_POST, COLLECT_MAX_ATTEMPTS);

    private final AppSettingRepository settings;
    private final DiscoverProperties discoverProps;
    private final QualifyProperties qualifyProps;
    private final CollectProperties collectProps;

    public SettingsService(AppSettingRepository settings, DiscoverProperties discoverProps,
                           QualifyProperties qualifyProps, CollectProperties collectProps) {
        this.settings = settings;
        this.discoverProps = discoverProps;
        this.qualifyProps = qualifyProps;
        this.collectProps = collectProps;
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
    public int backfillMonths() {
        return effective(COLLECT_BACKFILL_MONTHS);
    }

    @Transactional(readOnly = true)
    public int trackWindowDays() {
        return effective(COLLECT_TRACK_WINDOW_DAYS);
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
        return settings.findById(key)
                .map(s -> new SettingView(key, Integer.parseInt(s.getValue()), def, true))
                .orElseGet(() -> new SettingView(key, def, def, false));
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
            case COLLECT_BACKFILL_MONTHS -> collectProps.backfillMonths();
            case COLLECT_TRACK_WINDOW_DAYS -> collectProps.trackWindowDays();
            case COLLECT_BATCH_LIMIT -> collectProps.batchLimit();
            case COLLECT_COMMENTS_PER_POST -> collectProps.commentsPerPost();
            case COLLECT_MAX_ATTEMPTS -> collectProps.maxAttempts();
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
