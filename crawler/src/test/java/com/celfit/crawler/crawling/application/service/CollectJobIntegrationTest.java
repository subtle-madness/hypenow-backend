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
import com.celfit.crawler.common.config.CollectProperties;
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
    static final String USERNAME_BACKFILL = "it-collect-revisit-backfill";
    static final String USERNAME_DUE = "it-collect-revisit-due";
    static final String USERNAME_RECENT = "it-collect-revisit-recent";
    static final String USERNAME_NOT_BEAUTY = "it-collect-not-beauty";
    static final String USERNAME_UNJUDGED = "it-collect-beauty-unjudged";

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
        jdbc.update("delete from influencer where username in (?, ?, ?, ?, ?)",
                USERNAME_BACKFILL, USERNAME_DUE, USERNAME_RECENT, USERNAME_NOT_BEAUTY, USERNAME_UNJUDGED);
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
        // QUALIFIED + 뷰티 확정 인플루언서 1명 — 첫 방문(백필) 대상
        Influencer inf = new Influencer(USERNAME);
        inf.setStatus(InfluencerStatus.QUALIFIED);
        inf.setBeauty(true);
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
        when(profileSource.fetchAndSupplement(any(), any(), any()))
                .thenReturn(new CrawlExecutor.Execution(profileRun.getId(), List.of(profileItem)));

        CommentSourceSelector commentSource = mock(CommentSourceSelector.class); // 빈 열거 → 댓글 호출 없음

        CollectJob job = new CollectJob(new CollectProperties(10, 50, 3, 7, false),
                influencers, rawProfiles, rawMediaPages, contents,
                new ContentUpserter(contents, clock), rawComments,
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

    // ---------------------------------------------------------------------
    // findCollectTargets가 재방문 주기(revisitBefore)를 실제 JPQL로 반영하는지 — mock으론 검증 불가.
    // 백필(첫 수집 전) 대상이 가장 먼저, 주기 지난 추적 대상이 그다음, 주기 안 지난 최근 방문자는
    // 아예 빠져야 한다.
    // ---------------------------------------------------------------------
    @Test
    void findCollectTargets는_재방문_주기_안_지난_QUALIFIED를_제외하고_백필을_먼저_반환한다() {
        Instant now = clock.instant();
        Instant revisitBefore = now.minus(java.time.Duration.ofDays(settings.revisitIntervalDays()));
        long backfillBefore = influencers.countBackfillPending();

        Influencer backfillInf = new Influencer(USERNAME_BACKFILL);
        backfillInf.setStatus(InfluencerStatus.QUALIFIED);
        backfillInf.setBeauty(true);
        // firstCollectedAt/lastCollectedAt 모두 null — 백필 대상
        Long backfillId = influencers.save(backfillInf).getId();

        Influencer dueInf = new Influencer(USERNAME_DUE);
        dueInf.setStatus(InfluencerStatus.QUALIFIED);
        dueInf.setBeauty(true);
        Instant longAgo = revisitBefore.minusSeconds(3600); // 주기보다 더 오래 전 방문 — 대상
        dueInf.setFirstCollectedAt(longAgo);
        dueInf.setLastCollectedAt(longAgo);
        Long dueId = influencers.save(dueInf).getId();

        Influencer recentInf = new Influencer(USERNAME_RECENT);
        recentInf.setStatus(InfluencerStatus.QUALIFIED);
        recentInf.setBeauty(true);
        Instant justNow = now.minusSeconds(60); // 주기 안 지남 — 대상 제외
        recentInf.setFirstCollectedAt(justNow);
        recentInf.setLastCollectedAt(justNow);
        Long recentId = influencers.save(recentInf).getId();

        // 비뷰티·미판정은 QUALIFIED여도 수집 대상이 아니다 — collect는 뷰티 계정만 방문한다
        Influencer notBeautyInf = new Influencer(USERNAME_NOT_BEAUTY);
        notBeautyInf.setStatus(InfluencerStatus.QUALIFIED);
        notBeautyInf.setBeauty(false);
        Long notBeautyId = influencers.save(notBeautyInf).getId();

        Influencer unjudgedInf = new Influencer(USERNAME_UNJUDGED);
        unjudgedInf.setStatus(InfluencerStatus.QUALIFIED);  // beauty null — 미판정
        Long unjudgedId = influencers.save(unjudgedInf).getId();

        List<Influencer> targets = influencers.findCollectTargets(revisitBefore,
                org.springframework.data.domain.PageRequest.of(0, 100));
        List<Long> targetIds = targets.stream().map(Influencer::getId).toList();

        assertThat(targetIds).contains(backfillId, dueId);
        assertThat(targetIds).doesNotContain(recentId, notBeautyId, unjudgedId);
        assertThat(targetIds.indexOf(backfillId)).isLessThan(targetIds.indexOf(dueId));

        assertThat(influencers.countTrackDue(revisitBefore)).isGreaterThanOrEqualTo(1L);
        // 대시보드 READY 카운트도 뷰티만 — 백필 대기 증가분은 뷰티 백필 1명뿐이어야 한다
        assertThat(influencers.countBackfillPending()).isEqualTo(backfillBefore + 1);
    }

    // ---------------------------------------------------------------------
    // findReelsTargets — 뷰티만, 릴스 백필(last_reels_at null) 우선, 주기 안 지난 것은 제외.
    // collect 북키핑(last_collected_at)과 독립이라 프로필 수집 이력이 릴스 대기에 영향 없다.
    // ---------------------------------------------------------------------
    @Test
    void findReelsTargets는_뷰티만_릴스_주기_기준으로_선정한다() {
        Instant now = clock.instant();
        Instant revisitBefore = now.minus(java.time.Duration.ofDays(settings.revisitIntervalDays()));

        // 프로필 수집이 안 된 릴스 백필 — id가 더 낮아도(먼저 저장) 수집 완료 계정보다 뒤여야 한다
        Influencer plainBackfill = new Influencer(USERNAME_UNJUDGED);
        plainBackfill.setStatus(InfluencerStatus.QUALIFIED);
        plainBackfill.setBeauty(true);
        Long plainBackfillId = influencers.save(plainBackfill).getId();

        Influencer backfill = new Influencer(USERNAME_BACKFILL);
        backfill.setStatus(InfluencerStatus.QUALIFIED);
        backfill.setBeauty(true);
        backfill.setLastCollectedAt(now.minusSeconds(60));  // 프로필 수집 이력은 릴스 대기와 무관
        Long backfillId = influencers.save(backfill).getId();

        Influencer due = new Influencer(USERNAME_DUE);
        due.setStatus(InfluencerStatus.QUALIFIED);
        due.setBeauty(true);
        due.setLastReelsAt(revisitBefore.minusSeconds(3600));  // 주기 지남 — 대상
        Long dueId = influencers.save(due).getId();

        Influencer recent = new Influencer(USERNAME_RECENT);
        recent.setStatus(InfluencerStatus.QUALIFIED);
        recent.setBeauty(true);
        recent.setLastReelsAt(now.minusSeconds(60));  // 주기 안 지남 — 제외
        Long recentId = influencers.save(recent).getId();

        Influencer notBeauty = new Influencer(USERNAME_NOT_BEAUTY);
        notBeauty.setStatus(InfluencerStatus.QUALIFIED);
        notBeauty.setBeauty(false);   // 비뷰티 — 제외
        Long notBeautyId = influencers.save(notBeauty).getId();

        List<Influencer> targets = influencers.findReelsTargets(revisitBefore,
                org.springframework.data.domain.PageRequest.of(0, 100));
        List<Long> targetIds = targets.stream().map(Influencer::getId).toList();

        assertThat(targetIds).contains(backfillId, dueId, plainBackfillId);
        assertThat(targetIds).doesNotContain(recentId, notBeautyId);
        assertThat(targetIds.indexOf(backfillId)).isLessThan(targetIds.indexOf(dueId));  // 백필 우선
        // 백필끼리는 프로필 수집 완료 계정 우선 — 피드만 되고 릴스가 안 된 "짝 안 맞는" 계정부터 채운다
        assertThat(targetIds.indexOf(backfillId)).isLessThan(targetIds.indexOf(plainBackfillId));

        assertThat(influencers.countReelsDue(revisitBefore)).isGreaterThanOrEqualTo(2L);
    }
}
