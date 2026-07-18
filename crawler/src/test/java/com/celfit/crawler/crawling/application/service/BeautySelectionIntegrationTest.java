package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.crawling.application.port.out.CrawlRunRepository;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.domain.CrawlRun;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 비뷰티 재검 파이프라인(재스냅샷 → 재판정)의 선정 쿼리 통합 테스트 — 캡션 없는 재료로
 * 비뷰티 판정된 계정만 골라 재수집하고, 재료가 갱신된 비뷰티만 재판정하는 규칙을 고정한다.
 */
class BeautySelectionIntegrationTest extends IntegrationTest {

    static final String PREFIX = "it-beauty-sel-";
    static final Instant JUDGED = Instant.parse("2026-07-15T00:00:00Z");

    @Autowired InfluencerRepository influencers;
    @Autowired RawProfileRepository rawProfiles;
    @Autowired CrawlRunRepository crawlRuns;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("delete from raw_profile where influencer_id in (select id from influencer where username like ?)", PREFIX + "%");
        jdbc.update("delete from crawl_run where target_username like ?", PREFIX + "%");
        jdbc.update("delete from influencer where username like ?", PREFIX + "%");
    }

    Influencer notBeauty(String name, Instant judgedAt) {
        Influencer inf = new Influencer(PREFIX + name);
        inf.setStatus(InfluencerStatus.QUALIFIED);
        inf.setBeauty(false);
        inf.setBeautySource(Influencer.BEAUTY_SOURCE_CLAUDE);
        inf.setBeautyJudgedAt(judgedAt);
        return influencers.save(inf);
    }

    Long runId() {
        return crawlRuns.save(new CrawlRun(JobName.QUALIFY, TriggerType.MANUAL,
                null, PREFIX + "run", "it-source", Instant.now())).getId();
    }

    void profile(Influencer inf, RawSource source, Instant capturedAt, Long runId) {
        rawProfiles.save(new RawProfile(inf.getId(), runId, source, Map.of("k", "v"), capturedAt));
    }

    @Test
    void 재판정_선정은_판정_후_재료가_갱신된_비뷰티만_고른다() {
        Long run = runId();

        // 대상: 비뷰티 + 판정 이후 새 스냅샷
        Influencer refreshed = notBeauty("refreshed", JUDGED);
        profile(refreshed, RawSource.HIKER_MOBILE, JUDGED.minusSeconds(3600), run);
        profile(refreshed, RawSource.SELF_GQL, JUDGED.plusSeconds(3600), run);

        // 제외: 비뷰티지만 판정 이후 재료 갱신 없음
        Influencer stale = notBeauty("stale", JUDGED);
        profile(stale, RawSource.HIKER_MOBILE, JUDGED.minusSeconds(3600), run);

        // 제외: 뷰티 판정분 — 재료가 갱신돼도 재검 안 함
        Influencer beauty = notBeauty("beauty", JUDGED);
        beauty.setBeauty(true);
        influencers.save(beauty);
        profile(beauty, RawSource.SELF_GQL, JUDGED.plusSeconds(3600), run);

        // 제외: MANUAL 판정 — 절대 덮지 않는다
        Influencer manual = notBeauty("manual", JUDGED);
        manual.setBeautySource(Influencer.BEAUTY_SOURCE_MANUAL);
        influencers.save(manual);
        profile(manual, RawSource.SELF_GQL, JUDGED.plusSeconds(3600), run);

        // 제외: raw_profile 자체가 없음 — 재판정할 재료가 없다
        notBeauty("no-profile", JUDGED);

        List<Influencer> out = influencers.findRejudgeTargets(
                InfluencerStatus.QUALIFIED, Influencer.BEAUTY_SOURCE_CLAUDE, PageRequest.of(0, 100));

        assertThat(out.stream().map(Influencer::getUsername)
                .filter(u -> u.startsWith(PREFIX)))
                .containsExactly(PREFIX + "refreshed");
    }

    @Test
    void 재판정_선정은_판정이_오래된_계정부터_시각_미기록이_가장_먼저다() {
        // 실패 배치(옛 beauty_judged_at 유지)가 다음 실행에서 먼저 재시도돼, 전체를 다시
        // 돌리지 않아도 배치 한도만큼으로 실패분을 채울 수 있다.
        Long run = runId();
        Influencer fresh = notBeauty("order-fresh", JUDGED);                       // 최근 판정 — 뒤로
        profile(fresh, RawSource.SELF_GQL, JUDGED.plusSeconds(3600), run);
        Influencer stale = notBeauty("order-stale", JUDGED.minusSeconds(86400));   // 옛 판정 — 먼저
        profile(stale, RawSource.SELF_GQL, JUDGED.plusSeconds(3600), run);
        Influencer legacy = notBeauty("order-legacy", null);                       // 시각 미기록 — 가장 먼저
        profile(legacy, RawSource.SELF_GQL, JUDGED.plusSeconds(3600), run);

        List<Long> order = influencers.findRejudgeTargets(
                        InfluencerStatus.QUALIFIED, Influencer.BEAUTY_SOURCE_CLAUDE, PageRequest.of(0, 1000))
                .stream().map(Influencer::getId)
                .filter(List.of(fresh.getId(), stale.getId(), legacy.getId())::contains).toList();

        assertThat(order).containsExactly(legacy.getId(), stale.getId(), fresh.getId());
    }

    @Test
    void 재스냅샷_선정은_최신_프로필이_캡션_없는_소스인_비뷰티만_고른다() {
        Long run = runId();
        List<RawSource> captionless = List.of(RawSource.HIKER_MOBILE, RawSource.DATALIKERS);

        // 대상: 비뷰티 + 최신 프로필이 HIKER_MOBILE
        Influencer hiker = notBeauty("hiker", JUDGED);
        profile(hiker, RawSource.HIKER_MOBILE, JUDGED.minusSeconds(3600), run);

        // 대상: 비뷰티 + 최신 프로필이 DATALIKERS
        Influencer dl = notBeauty("dl", JUDGED);
        profile(dl, RawSource.DATALIKERS, JUDGED.minusSeconds(3600), run);

        // 제외: 옛 HIKER가 있어도 최신이 SELF_GQL이면 이미 캡션 재료 확보 — 재수집 불필요
        Influencer already = notBeauty("already", JUDGED);
        profile(already, RawSource.HIKER_MOBILE, JUDGED.minusSeconds(7200), run);
        profile(already, RawSource.SELF_GQL, JUDGED.minusSeconds(3600), run);

        // 제외: 뷰티 판정분
        Influencer beauty = notBeauty("beauty2", JUDGED);
        beauty.setBeauty(true);
        influencers.save(beauty);
        profile(beauty, RawSource.HIKER_MOBILE, JUDGED.minusSeconds(3600), run);

        // 제외: raw_profile 없음
        notBeauty("no-profile2", JUDGED);

        List<Influencer> out = influencers.findResnapshotTargets(
                InfluencerStatus.QUALIFIED, Influencer.BEAUTY_SOURCE_CLAUDE, captionless, PageRequest.of(0, 100));

        assertThat(out.stream().map(Influencer::getUsername)
                .filter(u -> u.startsWith(PREFIX)))
                .containsExactlyInAnyOrder(PREFIX + "hiker", PREFIX + "dl");
    }
}
