package com.celfit.crawler.admin;

import com.celfit.crawler.config.AggregateProperties;
import com.celfit.crawler.config.DiscoverProperties;
import com.celfit.crawler.domain.AppSetting;
import com.celfit.crawler.domain.AppSettingRepository;
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
    static final String DELAY_DAYS = "aggregate.delay-days";
    static final String BATCH_LIMIT = "aggregate.batch-limit";
    static final String CHUNK_SIZE = "aggregate.chunk-size";
    static final String COMMENTS_PER_POST = "aggregate.comments-per-post";
    static final String MAX_ATTEMPTS = "aggregate.max-attempts";

    private static final List<String> KEYS = List.of(
            RESULTS_LIMIT, DELAY_DAYS, BATCH_LIMIT, CHUNK_SIZE, COMMENTS_PER_POST, MAX_ATTEMPTS);

    private final AppSettingRepository settings;
    private final DiscoverProperties discoverProps;
    private final AggregateProperties aggregateProps;

    public SettingsService(AppSettingRepository settings, DiscoverProperties discoverProps,
                           AggregateProperties aggregateProps) {
        this.settings = settings;
        this.discoverProps = discoverProps;
        this.aggregateProps = aggregateProps;
    }

    @Transactional(readOnly = true)
    public int resultsLimit() {
        return effective(RESULTS_LIMIT);
    }

    @Transactional(readOnly = true)
    public int delayDays() {
        return effective(DELAY_DAYS);
    }

    @Transactional(readOnly = true)
    public int batchLimit() {
        return effective(BATCH_LIMIT);
    }

    @Transactional(readOnly = true)
    public int chunkSize() {
        return effective(CHUNK_SIZE);
    }

    @Transactional(readOnly = true)
    public int commentsPerPost() {
        return effective(COMMENTS_PER_POST);
    }

    @Transactional(readOnly = true)
    public int maxAttempts() {
        return effective(MAX_ATTEMPTS);
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
            case DELAY_DAYS -> aggregateProps.delayDays();
            case BATCH_LIMIT -> aggregateProps.batchLimit();
            case CHUNK_SIZE -> aggregateProps.chunkSize();
            case COMMENTS_PER_POST -> aggregateProps.commentsPerPost();
            case MAX_ATTEMPTS -> aggregateProps.maxAttempts();
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "알 수 없는 설정 키: " + key);
        };
    }

    private void validate(String key, int value) {
        int min = DELAY_DAYS.equals(key) ? 0 : 1;
        if (value < min) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "잘못된 값: " + key + "=" + value + " (최소 " + min + ")");
        }
    }
}
