package com.celfit.crawler.common.config;

import com.celfit.crawler.crawling.adapter.out.apify.ApifyProperties;
import com.celfit.crawler.crawling.adapter.out.hiker.HikerProperties;
import com.celfit.crawler.crawling.adapter.out.instagram.DirectCommentProperties;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.adapter.out.apify.Sleeper;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableConfigurationProperties({ApifyProperties.class, DiscoverProperties.class,
        CollectProperties.class, ScheduleProperties.class, DirectCommentProperties.class,
        HikerProperties.class, QualifyProperties.class})
public class CrawlerConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Sleeper sleeper() {
        return duration -> {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApifyException("폴링 대기 중단", e);
            }
        };
    }

    /** 잡 비동기 실행용 — 테스트는 SyncTaskExecutor로 대체해 결정적으로 만든다. */
    @Bean
    TaskExecutor jobTaskExecutor() {
        return new SimpleAsyncTaskExecutor("job-");
    }

    /** CollectJob이 인플루언서 방문 1회 = 트랜잭션 1개로 감싸는 데 쓴다(배치 전체 단일 트랜잭션 방지). */
    @Bean
    TransactionTemplate collectTransactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}
