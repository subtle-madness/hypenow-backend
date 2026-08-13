package com.celfit.crawler.common.config;

import com.celfit.crawler.crawling.adapter.out.apify.ApifyProperties;
import com.celfit.crawler.crawling.adapter.out.datalikers.CountingDataLikersHttp;
import com.celfit.crawler.crawling.adapter.out.datalikers.DataLikersHttp;
import com.celfit.crawler.crawling.adapter.out.datalikers.DataLikersProperties;
import com.celfit.crawler.crawling.adapter.out.datalikers.JdkDataLikersHttp;
import com.celfit.crawler.crawling.adapter.out.hiker.CountingHikerHttp;
import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.adapter.out.hiker.HikerProperties;
import com.celfit.crawler.crawling.adapter.out.hiker.JdkHikerHttp;
import com.celfit.crawler.crawling.adapter.out.instagram.DirectCommentProperties;
import com.celfit.crawler.crawling.adapter.out.instagram.ProxyProperties;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.PaidCallCounter;
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
        HikerProperties.class, QualifyProperties.class, DataLikersProperties.class,
        ProxyProperties.class, SimilarProperties.class, BeautyProperties.class, ReelsProperties.class})
public class CrawlerConfig {

    // 존은 "오늘 자정" 경계 계산(데일리 대시보드·재방문 대상 선정)에만 쓰인다 — 저장은 전부
    // Instant(절대 시각)라 존과 무관. 운영자가 한국 기준으로 보므로 KST 자정에 하루가 리셋된다.
    @Bean
    Clock clock() {
        return Clock.system(java.time.ZoneId.of("Asia/Seoul"));
    }

    /**
     * HikerAPI 전송 = 실제 전송(JdkHikerHttp) + 과금 콜 집계 데코레이터. 데코레이터가 성공 응답마다
     * PaidCallCounter에 +1해, 실행이 중간에 실패해도 그때까지 산 요청 수가 crawl_run에 남는다.
     */
    @Bean
    HikerHttp hikerHttp(HikerProperties props, PaidCallCounter paidCallCounter) {
        return new CountingHikerHttp(new JdkHikerHttp(props), paidCallCounter);
    }

    /** DataLikers 전송도 같은 이유로 감싼다 — 유료 전송이 둘이라 데코레이터도 둘이다. */
    @Bean
    DataLikersHttp dataLikersHttp(DataLikersProperties props, PaidCallCounter paidCallCounter) {
        return new CountingDataLikersHttp(new JdkDataLikersHttp(props), paidCallCounter);
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

    /**
     * 배치 전체 단일 트랜잭션(idle-in-transaction 커넥션 점유·부분 실패 시 전체 롤백)을 방지하는 공용
     * TransactionTemplate — CollectJob(방문 1회), BeautyJob(판정 배치 1회), SimilarJob(시드 1개)가
     * 각자의 작업 단위를 이걸로 감싼다. 이름은 최초 도입한 CollectJob 기준이지만 잡 전용이 아니다.
     */
    @Bean
    TransactionTemplate collectTransactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}
