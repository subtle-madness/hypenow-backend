package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.crawling.application.port.out.CrawlRunRepository;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.domain.BeautyClass;
import com.celfit.crawler.crawling.domain.CategoryClass;
import com.celfit.crawler.crawling.domain.CrawlRun;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawProfile;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

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
        jdbc.update("delete from raw_media_page where influencer_id in "
                + "(select id from influencer where username like ?)", PREFIX + "%");
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

    private Long mediaPage(Influencer inf, Instant capturedAt, Long runId, int itemCount) {
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            items.add(Map.of("media", Map.of("caption", Map.of("text", "캡션 " + i))));
        }
        return jdbc.queryForObject("""
                insert into raw_media_page (influencer_id, crawl_run_id, source, payload, captured_at)
                values (?, ?, 'HIKER_V2_CLIPS', ?::jsonb, ?) returning id""",
                Long.class, inf.getId(), runId,
                new ObjectMapper().writeValueAsString(Map.of("response", Map.of("items", items))),
                Timestamp.from(capturedAt));
    }

    private Influencer judged(String username, boolean beauty, Instant judgedAt, Short captionCount) {
        Influencer inf = new Influencer(PREFIX + username);
        inf.setStatus(InfluencerStatus.QUALIFIED);
        inf.classify(beauty ? BeautyClass.INFLUENCER : BeautyClass.NOT_BEAUTY,
                Influencer.BEAUTY_SOURCE_CLAUDE, "이유", "CATEGORY_ONLY");
        inf.setBeautyJudgedAt(judgedAt);
        inf.setBeautyCaptionCount(captionCount);
        return influencers.save(inf);
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
    void 판정_근거_필드가_저장되고_읽힌다() {
        Influencer inf = notBeauty("evidence", Instant.parse("2026-07-01T00:00:00Z"));
        inf.setBeautyCaptionCount((short) 0);
        inf.setBeautyBasis("CATEGORY_ONLY");
        influencers.save(inf);

        Influencer loaded = influencers.findByUsername(PREFIX + "evidence").orElseThrow();
        assertThat(loaded.getBeautyCaptionCount()).isEqualTo((short) 0);
        assertThat(loaded.getBeautyBasis()).isEqualTo("CATEGORY_ONLY");
    }

    private static final Instant JUDGED_CAPTION = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant AFTER = Instant.parse("2026-07-10T00:00:00Z");
    private static final Instant BEFORE = Instant.parse("2026-06-01T00:00:00Z");

    @Test
    void 캡션_재판정은_뷰티_판정분도_대상으로_삼는다() {
        Influencer inf = judged("fp", true, JUDGED_CAPTION, (short) 0);
        mediaPage(inf, AFTER, runId(), 5);

        var targets = influencers.findCaptionRejudgeTargets(
                InfluencerStatus.QUALIFIED.name(), Influencer.BEAUTY_SOURCE_CLAUDE, 3, PageRequest.of(0, 10));

        assertThat(targets).extracting(Influencer::getUsername).contains(PREFIX + "fp");
    }

    @Test
    void 캡션_재판정은_아이템이_부족한_페이지를_무시한다() {
        Influencer inf = judged("thin", true, JUDGED_CAPTION, (short) 0);
        mediaPage(inf, AFTER, runId(), 2);

        var targets = influencers.findCaptionRejudgeTargets(
                InfluencerStatus.QUALIFIED.name(), Influencer.BEAUTY_SOURCE_CLAUDE, 3, PageRequest.of(0, 10));

        assertThat(targets).extracting(Influencer::getUsername).doesNotContain(PREFIX + "thin");
    }

    @Test
    void 캡션_재판정은_판정_이전에_쌓인_페이지를_무시한다() {
        Influencer inf = judged("stale", true, JUDGED_CAPTION, (short) 0);
        mediaPage(inf, BEFORE, runId(), 5);

        var targets = influencers.findCaptionRejudgeTargets(
                InfluencerStatus.QUALIFIED.name(), Influencer.BEAUTY_SOURCE_CLAUDE, 3, PageRequest.of(0, 10));

        assertThat(targets).extracting(Influencer::getUsername).doesNotContain(PREFIX + "stale");
    }

    @Test
    void 캡션_재판정은_이미_캡션으로_판정된_계정을_제외한다() {
        Influencer inf = judged("done", true, JUDGED_CAPTION, (short) 4);
        mediaPage(inf, AFTER, runId(), 5);

        var targets = influencers.findCaptionRejudgeTargets(
                InfluencerStatus.QUALIFIED.name(), Influencer.BEAUTY_SOURCE_CLAUDE, 3, PageRequest.of(0, 10));

        assertThat(targets).extracting(Influencer::getUsername).doesNotContain(PREFIX + "done");
    }

    @Test
    void 캡션_재판정은_기록_이전_판정분을_제외한다() {
        Influencer inf = judged("legacy", true, JUDGED_CAPTION, null);
        mediaPage(inf, AFTER, runId(), 5);

        var targets = influencers.findCaptionRejudgeTargets(
                InfluencerStatus.QUALIFIED.name(), Influencer.BEAUTY_SOURCE_CLAUDE, 3, PageRequest.of(0, 10));

        assertThat(targets).extracting(Influencer::getUsername).doesNotContain(PREFIX + "legacy");
    }

    @Test
    void 캡션_재판정은_수동_판정을_제외한다() {
        Influencer inf = judged("manual", true, JUDGED_CAPTION, (short) 0);
        inf.setBeautySource(Influencer.BEAUTY_SOURCE_MANUAL);
        influencers.save(inf);
        mediaPage(inf, AFTER, runId(), 5);

        var targets = influencers.findCaptionRejudgeTargets(
                InfluencerStatus.QUALIFIED.name(), Influencer.BEAUTY_SOURCE_CLAUDE, 3, PageRequest.of(0, 10));

        assertThat(targets).extracting(Influencer::getUsername).doesNotContain(PREFIX + "manual");
    }

    @Test
    void fnb_판정이_저장되고_재조회된다() {
        Influencer inf = influencers.save(new Influencer(PREFIX + "fnb-roundtrip"));
        inf.classifyFnb(CategoryClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "레시피 계정", "CAPTION");
        inf.setFnbJudgedAt(Instant.parse("2026-08-24T00:00:00Z"));
        inf.setFnbCaptionCount((short) 5);
        influencers.save(inf);

        Influencer found = influencers.findByUsername(PREFIX + "fnb-roundtrip").orElseThrow();
        assertThat(found.getFnbClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(found.getFnb()).isTrue();
        assertThat(found.getFnbCompany()).isFalse();
        assertThat(found.getFnbSource()).isEqualTo(Influencer.BEAUTY_SOURCE_CLAUDE);
        assertThat(found.getFnbReason()).isEqualTo("레시피 계정");
        assertThat(found.getFnbBasis()).isEqualTo("CAPTION");
        assertThat(found.getFnbJudgedAt()).isEqualTo(Instant.parse("2026-08-24T00:00:00Z"));
        assertThat(found.getFnbCaptionCount()).isEqualTo((short) 5);
    }

}
