package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.Actors;
import com.celfit.crawler.crawling.application.port.out.ActorInputs;
import com.celfit.crawler.crawling.application.port.out.CommentFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.ShortCodes;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.CommentSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 기존 Apify 댓글 액터 경로. 액터 아이템을 postUrl→shortCode로 그룹해 원형 그대로 저장한다. */
@Component
public class ActorCommentFetcher implements CommentFetcher {

    private final CrawlExecutor executor;

    public ActorCommentFetcher(CrawlExecutor executor) {
        this.executor = executor;
    }

    @Override
    public CommentResult fetch(List<String> shortCodes, int commentsPerPost, TriggerType trigger) {
        List<String> postUrls = shortCodes.stream().map(ShortCodes::postUrl).toList();
        CrawlExecutor.Execution ex = executor.execute(JobName.COLLECT, trigger, null, null,
                Actors.COMMENT, ActorInputs.comments(postUrls, commentsPerPost));

        Map<String, List<Map<String, Object>>> pagesByCode = new LinkedHashMap<>();
        for (Map<String, Object> item : ex.items()) {
            String shortCode = item.get("postUrl") instanceof String url
                    ? ShortCodes.fromUrl(url).orElse(null) : null;
            if (shortCode == null) continue;
            pagesByCode.computeIfAbsent(shortCode, k -> new ArrayList<>()).add(item);
        }
        return new CommentResult(ex.runId(), pagesByCode);
    }

    @Override
    public CommentSource source() {
        return CommentSource.ACTOR;
    }

    @Override
    public RawSource rawSource() {
        return RawSource.APIFY_ACTOR;
    }
}
