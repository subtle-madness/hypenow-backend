package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentStatus;
import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.port.out.CommentFetcher;
import com.celfit.crawler.crawling.application.port.out.DetailFetcher;
import com.celfit.crawler.crawling.application.port.out.RawCommentRepository;
import com.celfit.crawler.crawling.application.port.out.RawPostDetailRepository;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AggregateJob이 상세 fetch를 더 이상 CrawlExecutor로 직접 호출하지 않고
 * DetailSourceSelector.forType(type).fetch(...) 경유로 호출하는지 배선을 검증한다.
 * (타입별로 올바른 fetcher가 선택되는지 자체는 Task 6의 DetailSourceSelectorTest가 검증한다.)
 */
class AggregateJobDetailRoutingTest {

    @Test
    void aggregateChunk가_타입별_셀렉터를_경유한다() {
        ContentRepository contents = mock(ContentRepository.class);
        RawPostDetailRepository rawDetails = mock(RawPostDetailRepository.class);
        RawCommentRepository rawComments = mock(RawCommentRepository.class);
        CrawlExecutor executor = mock(CrawlExecutor.class);
        SettingsService settings = mock(SettingsService.class);
        CommentSourceSelector commentSource = mock(CommentSourceSelector.class);
        JobProgress progress = mock(JobProgress.class);
        DetailSourceSelector detailSource = mock(DetailSourceSelector.class);

        DetailFetcher fetcher = mock(DetailFetcher.class);
        when(detailSource.forType(any())).thenReturn(fetcher);
        when(fetcher.fetch(any(), any(), any()))
                .thenReturn(new CrawlExecutor.Execution(1L, List.of()));

        CommentFetcher commentFetcher = mock(CommentFetcher.class);
        when(commentSource.current()).thenReturn(commentFetcher);
        when(commentFetcher.fetch(any(), eq(0), any()))
                .thenReturn(new CrawlExecutor.Execution(2L, List.of()));

        when(settings.delayDays()).thenReturn(3);
        when(settings.batchLimit()).thenReturn(100);
        when(settings.chunkSize()).thenReturn(50);
        when(settings.commentsPerPost()).thenReturn(0);
        when(settings.maxAttempts()).thenReturn(3);

        Content reel = new Content("reel1", ContentType.REELS, "kim",
                Instant.now(), 1L, "메이크업", Instant.now());
        reel.setStatus(ContentStatus.QUALIFIED);
        when(contents.findDue(eq(ContentStatus.QUALIFIED), any(), any()))
                .thenReturn(List.of(reel));

        Clock clock = Clock.fixed(Instant.parse("2026-07-11T00:00:00Z"), ZoneOffset.UTC);

        AggregateJob job = new AggregateJob(contents, rawDetails, rawComments, executor,
                settings, clock, commentSource, progress, detailSource);

        job.run(TriggerType.MANUAL);

        verify(detailSource).forType(ContentType.REELS);
        verify(fetcher).fetch(List.of("reel1"), ContentType.REELS, TriggerType.MANUAL);
        // 상세 수집이 셀렉터 경유로 바뀌었으니 executor는 상세 fetch에 더 이상 쓰이지 않는다.
        org.mockito.Mockito.verifyNoInteractions(executor);
        assertThat(detailSource).isNotNull();
    }
}
