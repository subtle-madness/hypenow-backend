package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.CrawlRun;
import com.celfit.crawler.crawling.application.port.out.CrawlRunRepository;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실 Postgres 통합 테스트 — qualify가 배치 전체 단일 트랜잭션이던 시절, 실행 중 예외·프로세스
 * 종료가 앞선 청크의 프로필 확보분까지 통째로 롤백시키던 사고(2026-07-16 실측)를 재현해,
 * 청크 단위 커밋(뒤 청크가 죽어도 앞 청크 보존)을 고정한다.
 */
class QualifyJobIntegrationTest extends IntegrationTest {

    static final String PREFIX = "it-qualify-chunk-";

    @Autowired InfluencerRepository influencers;
    @Autowired RawProfileRepository rawProfiles;
    @Autowired CrawlRunRepository crawlRuns;
    @Autowired SettingsService settings;
    @Autowired Clock clock;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("delete from raw_profile where influencer_id in (select id from influencer where username like ?)", PREFIX + "%");
        jdbc.update("delete from crawl_run where target_username like ?", PREFIX + "%");
        jdbc.update("delete from influencer where username like ?", PREFIX + "%");
    }

    @Test
    void 뒤_청크가_죽어도_앞_청크의_프로필_확보분은_커밋되어_있다() {
        // 51명(청크 50 + 1) — 첫 청크는 성공, 둘째 청크 fetch는 런타임 예외로 죽는다.
        List<Influencer> saved = new ArrayList<>();
        for (int n = 0; n < 51; n++) {
            Influencer inf = new Influencer(PREFIX + String.format("%02d", n));
            inf.setStatus(InfluencerStatus.DISCOVERED);   // followers null — 프로필 확보 대상
            saved.add(influencers.save(inf));
        }
        String firstUsername = saved.get(0).getUsername();

        CrawlRun run = crawlRuns.save(new CrawlRun(JobName.QUALIFY, TriggerType.MANUAL,
                null, firstUsername, "it-qualify-source", Instant.now()));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("username", firstUsername);
        item.put("followersCount", 5000L);   // 판정 범위(3000~50000) 안 — QUALIFIED 기대
        item.put("userId", "IT-Q-1");

        ProfileSourceSelector selector = mock(ProfileSourceSelector.class);
        when(selector.currentSource()).thenReturn(RawSource.APIFY_ACTOR);
        when(selector.fetchAndSupplement(any(), anyList(), any()))
                .thenReturn(new CrawlExecutor.Execution(run.getId(), List.of(item)))   // 1청크 성공
                .thenThrow(new IllegalStateException("둘째 청크에서 프로세스가 죽는 상황"));  // 2청크 죽음

        QualifyJob job = new QualifyJob(influencers, rawProfiles, selector, settings, clock,
                new TransactionTemplate(txManager));
        var summary = job.run(TriggerType.MANUAL, false);

        // 뒤 청크 실패는 격리 — 잡은 정상 종료하고 실패 청크로 집계된다
        assertThat(summary.failedChunks()).isEqualTo(1);
        assertThat(summary.profiled()).isEqualTo(1);

        // 핵심: 앞 청크의 확보분이 실제 DB에 커밋돼 있다(단일 트랜잭션이면 통째로 롤백됐다)
        Influencer reloaded = influencers.findById(saved.get(0).getId()).orElseThrow();
        assertThat(reloaded.getFollowers()).isEqualTo(5000L);
        assertThat(reloaded.getLastProfiledAt()).isNotNull();
        assertThat(reloaded.getStatus()).isEqualTo(InfluencerStatus.QUALIFIED);  // 판정도 영속
        assertThat(rawProfiles.findTopByInfluencerIdOrderByCapturedAtDesc(saved.get(0).getId())).isPresent();
    }
}
