package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.Actors;
import com.celfit.crawler.crawling.application.port.out.ActorInputs;
import com.celfit.crawler.crawling.application.port.out.CommentFetcher;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.ShortCodes;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.CommentSource;
import java.util.List;
import org.springframework.stereotype.Component;

/** 기존 Apify 댓글 액터 경로. */
@Component
public class ActorCommentFetcher implements CommentFetcher {

    private final CrawlExecutor executor;

    public ActorCommentFetcher(CrawlExecutor executor) {
        this.executor = executor;
    }

    @Override
    public CrawlExecutor.Execution fetch(List<String> shortCodes, int limit, TriggerType trigger) {
        List<String> postUrls = shortCodes.stream().map(ShortCodes::postUrl).toList();
        return executor.execute(JobName.COLLECT, trigger, null, null,
                Actors.COMMENT, ActorInputs.comments(postUrls, limit));
    }

    @Override
    public CommentSource source() {
        return CommentSource.ACTOR;
    }
}
