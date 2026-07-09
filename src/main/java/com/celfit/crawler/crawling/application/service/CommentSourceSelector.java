package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.CommentFetcher;
import com.celfit.crawler.settings.application.service.CommentSourceSetting;
import com.celfit.crawler.settings.domain.CommentSource;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** comment.source 설정으로 활성 CommentFetcher 선택. 미존재 시 ACTOR 폴백. */
@Service
public class CommentSourceSelector {

    private final Map<CommentSource, CommentFetcher> bySource;
    private final CommentSourceSetting setting;

    public CommentSourceSelector(List<CommentFetcher> fetchers, CommentSourceSetting setting) {
        this.bySource = fetchers.stream()
                .collect(Collectors.toMap(CommentFetcher::source, Function.identity()));
        this.setting = setting;
    }

    public CommentFetcher current() {
        CommentFetcher f = bySource.get(setting.current());
        return f != null ? f : bySource.get(CommentSource.ACTOR);
    }
}
