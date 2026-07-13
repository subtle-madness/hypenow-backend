package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.crawling.application.port.out.CrawlRunRepository;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawCommentRepository;
import com.celfit.crawler.crawling.application.port.out.RawMediaPageRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.application.port.out.UserMediaPageFetcher;
import com.celfit.crawler.crawling.domain.CrawlRun;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.service.SettingsService;
import java.time.Clock;
import java.time.Instant;
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
 * 실 Postgres(Testcontainers) 통합 테스트 — 방문 단위 트랜잭션 전환 후 findCollectTargets가
 * 반환하는 Influencer는 detached라, visit()의 방문 북키핑(first/last_collected_at·followers)이
 * 명시적 save(merge) 없이는 DB에 저장되지 않는 회귀를 실제 flush/commit으로 잡는다.
 * (Mockito 단위 테스트는 세터 호출까지만 보므로 이 회귀를 놓친다.)
 *
 * 의도적으로 @Transactional을 붙이지 않는다 — 테스트 트랜잭션이 있으면 엔티티가 managed로 남아
 * 프로덕션(백그라운드 스레드, OSIV 없음)의 detached 상황이 재현되지 않는다. 커밋되는 행은
 * cleanup()에서 직접 지운다.
 */
class CollectJobIntegrationTest extends IntegrationTest {

    static final String USERNAME = "it-collect-bookkeeping-user";

    @Autowired InfluencerRepository influencers;
    @Autowired RawProfileRepository rawProfiles;
    @Autowired RawMediaPageRepository rawMediaPages;
    @Autowired ContentRepository contents;
    @Autowired RawCommentRepository rawComments;
    @Autowired CrawlRunRepository crawlRuns;
    @Autowired CrawlExecutor executor;
    @Autowired SettingsService settings;
    @Autowired Clock clock;
    @Autowired JobProgress progress;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("delete from raw_profile where influencer_id in (select id from influencer where username = ?)", USERNAME);
        jdbc.update("delete from raw_media_page where influencer_id in (select id from influencer where username = ?)", USERNAME);
        jdbc.update("delete from raw_run_item where crawl_run_id in (select id from crawl_run where target_username = ?)", USERNAME);
        jdbc.update("delete from crawl_run where target_username = ?", USERNAME);
        jdbc.update("delete from influencer where username = ?", USERNAME);
    }

    /** HIKER_GQL_MEDIAS 빈 페이지만 돌려주는 fake — 열거는 즉시 자연 종료된다. */
    static UserMediaPageFetcher emptyGqlFetcher() {
        return new UserMediaPageFetcher() {
            @Override
            public RawSource source() {
                return RawSource.HIKER_GQL_MEDIAS;
            }

            @Override
            public Map<String, Object> fetchPage(String userId, String cursor) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("items", List.of());
                m.put("more_available", false);
                return m;
            }
        };
    }

    @Test
    void run_후_influencer_방문_북키핑이_실제_DB에_저장된다() {
        // QUALIFIED 인플루언서 1명 — 첫 방문(백필) 대상
        Influencer inf = new Influencer(USERNAME);
        inf.setStatus(InfluencerStatus.QUALIFIED);
        Long infId = influencers.save(inf).getId();

        // 프로필 응답 mock의 runId는 raw_profile.crawl_run_id FK를 만족해야 하므로 실제 crawl_run을 만든다
        CrawlRun profileRun = crawlRuns.save(new CrawlRun(JobName.COLLECT, TriggerType.MANUAL,
                null, USERNAME, "it-profile-source", Instant.now()));

        Map<String, Object> profileItem = new LinkedHashMap<>();
        profileItem.put("username", USERNAME);
        profileItem.put("followersCount", 4321L);
        profileItem.put("userId", "IT-USER-1");
        ProfileSourceSelector profileSource = mock(ProfileSourceSelector.class);
        when(profileSource.currentSource()).thenReturn(RawSource.APIFY_ACTOR);
        when(profileSource.fetchAndSupplement(any(), any()))
                .thenReturn(new CrawlExecutor.Execution(profileRun.getId(), List.of(profileItem)));

        CommentSourceSelector commentSource = mock(CommentSourceSelector.class); // 빈 열거 → 댓글 호출 없음

        CollectJob job = new CollectJob(influencers, rawProfiles, rawMediaPages, contents, rawComments,
                List.of(emptyGqlFetcher()), profileSource, commentSource, executor, settings, clock,
                progress, new TransactionTemplate(txManager));

        var summary = job.run(TriggerType.MANUAL);
        assertThat(summary.visited()).isEqualTo(1);
        assertThat(summary.failedVisits()).isEqualTo(0);

        // 핵심 검증: 새 트랜잭션에서 DB를 다시 읽었을 때 방문 북키핑이 실제로 커밋돼 있어야 한다.
        Influencer reloaded = influencers.findById(infId).orElseThrow();
        assertThat(reloaded.getLastCollectedAt()).as("last_collected_at 미저장 = 매 실행 재선정(백필 무한 반복)").isNotNull();
        assertThat(reloaded.getFirstCollectedAt()).as("first_collected_at(백필 완료 표식)").isNotNull();
        assertThat(reloaded.getFollowers()).isEqualTo(4321L);
        assertThat(reloaded.getLastProfiledAt()).isNotNull();
    }
}
