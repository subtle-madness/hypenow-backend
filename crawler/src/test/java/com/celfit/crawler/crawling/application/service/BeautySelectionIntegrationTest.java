package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** 판정 전 상태의 QUALIFIED 계정 — 판정은 호출자가 원하는 축만 붙인다. */
    Influencer qualified(String name) {
        Influencer inf = new Influencer(PREFIX + name);
        inf.setStatus(InfluencerStatus.QUALIFIED);
        return inf;
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
                InfluencerStatus.QUALIFIED, Influencer.BEAUTY_SOURCE_CLAUDE,
                Instant.now(), PageRequest.of(0, 100));

        assertThat(out.stream().map(Influencer::getUsername)
                .filter(u -> u.startsWith(PREFIX)))
                .containsExactly(PREFIX + "refreshed");
    }

    @Test
    void 재판정_선정은_쿨다운_이내_판정을_제외한다() {
        Long run = runId();
        // 컷오프: 이 시각 이전 판정만 재판정 대상
        Instant cooldownBefore = JUDGED.plusSeconds(60);

        // 대상: 판정이 컷오프보다 오래됐고 판정 후 새 스냅샷이 있다
        Influencer old = notBeauty("cool-old", JUDGED);
        profile(old, RawSource.SELF_GQL, JUDGED.plusSeconds(3600), run);

        // 제외: 새 스냅샷은 있지만 판정이 컷오프 이후(쿨다운 이내)
        Influencer recent = notBeauty("cool-recent", JUDGED.plusSeconds(120));
        profile(recent, RawSource.SELF_GQL, JUDGED.plusSeconds(3600), run);

        List<Influencer> out = influencers.findRejudgeTargets(
                InfluencerStatus.QUALIFIED, Influencer.BEAUTY_SOURCE_CLAUDE,
                cooldownBefore, PageRequest.of(0, 100));

        assertThat(out.stream().map(Influencer::getUsername)
                .filter(u -> u.startsWith(PREFIX)))
                .containsExactly(PREFIX + "cool-old");
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
                        InfluencerStatus.QUALIFIED, Influencer.BEAUTY_SOURCE_CLAUDE,
                        Instant.now(), PageRequest.of(0, 1000))
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

    @Test
    void 백필_선정은_뷰티_판정_완료이고_fnb_미판정인_계정만_고른다() {
        Influencer judged = influencers.save(qualified("bf_judged"));      // beauty 판정됨, fnb NULL → 대상
        judged.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(judged);
        influencers.save(qualified("bf_unjudged"));                        // beauty NULL → 신규 경로 몫, 제외
        Influencer done = influencers.save(qualified("bf_done"));          // 둘 다 판정 → 제외
        done.classify(BeautyClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        done.classifyFnb(CategoryClass.NONE, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(done);

        var picked = influencers.findFnbBackfillTargets(
                InfluencerStatus.QUALIFIED, PageRequest.of(0, 10));

        assertThat(picked).extracting(Influencer::getUsername)
                .filteredOn(u -> u.startsWith(PREFIX))
                .containsExactly(PREFIX + "bf_judged");
    }

    @Test
    void 백필_잔여_카운트는_선정_쿼리와_같은_모수를_센다() {
        // 대시보드 "F&B 미판정 · 백필 잔여" 타일 — beauty IS NULL(신규 판정 대기)까지 세면
        // 백필이 다 끝나도 잔여가 0으로 안 떨어져 진행률을 오독한다.
        long before = influencers.countFnbBackfillRemaining(InfluencerStatus.QUALIFIED);

        Influencer judged = influencers.save(qualified("cnt_judged"));     // 백필 모수 → +1
        judged.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(judged);
        influencers.save(qualified("cnt_unjudged"));                       // beauty NULL → 신규 경로 몫
        Influencer done = influencers.save(qualified("cnt_done"));         // 둘 다 판정 → 제외
        done.classify(BeautyClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        done.classifyFnb(CategoryClass.NONE, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(done);

        assertThat(influencers.countFnbBackfillRemaining(InfluencerStatus.QUALIFIED))
                .isEqualTo(before + 1);
    }

    @Test
    void 백필_선정은_수동_뷰티_판정분도_대상으로_삼는다() {
        // 백필은 뷰티 축을 덮지 않으므로(BeautyJob의 fnbOnly 마스크) MANUAL을 제외할 이유가 없다 —
        // 수동 교정 계정도 F&B 축은 채워야 한다.
        Influencer manual = influencers.save(qualified("bf_manual"));
        manual.classify(BeautyClass.INFLUENCER, Influencer.BEAUTY_SOURCE_MANUAL, "수동", null);
        influencers.save(manual);

        var picked = influencers.findFnbBackfillTargets(
                InfluencerStatus.QUALIFIED, PageRequest.of(0, 10));

        assertThat(picked).extracting(Influencer::getUsername).contains(PREFIX + "bf_manual");
    }

    @Test
    void 수집_선정은_토글_on일_때만_F앤B_인플루언서를_포함하고_회사는_항상_제외한다() {
        Influencer fnbInf = influencers.save(qualified("gate_fnb"));
        fnbInf.classifyFnb(CategoryClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        fnbInf.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(fnbInf);
        Influencer fnbCo = influencers.save(qualified("gate_fnb_co"));
        fnbCo.classifyFnb(CategoryClass.COMPANY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        fnbCo.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(fnbCo);

        Instant future = Instant.now().plusSeconds(3600);
        var off = influencers.findCollectTargets(future, false, false, PageRequest.of(0, 1000));
        assertThat(off).extracting(Influencer::getUsername)
                .doesNotContain(PREFIX + "gate_fnb", PREFIX + "gate_fnb_co");

        var on = influencers.findCollectTargets(future, true, false, PageRequest.of(0, 1000));
        assertThat(on).extracting(Influencer::getUsername).contains(PREFIX + "gate_fnb");
        assertThat(on).extracting(Influencer::getUsername).doesNotContain(PREFIX + "gate_fnb_co");
    }

    @Test
    void 시드_선정도_토글을_따른다() {
        Influencer fnbInf = influencers.save(qualified("seed_fnb"));
        fnbInf.classifyFnb(CategoryClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        fnbInf.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(fnbInf);

        assertThat(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                InfluencerStatus.QUALIFIED, false, false, PageRequest.of(0, 1000)))
                .extracting(Influencer::getUsername).doesNotContain(PREFIX + "seed_fnb");
        assertThat(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                InfluencerStatus.QUALIFIED, true, false, PageRequest.of(0, 1000)))
                .extracting(Influencer::getUsername).contains(PREFIX + "seed_fnb");
    }

    @Test
    void 카운트_쿼리도_토글에_따라_F앤B_모수를_더한다() {
        // 대시보드 대기열 타일·예상 비용 카드가 선정 쿼리와 같은 모수를 보게 하는 게이트 —
        // 토글 off면 뷰티 축 카운트가 그대로여야 한다(운영 기본값이 off).
        Instant future = Instant.now().plusSeconds(3600);
        long backfillOff = influencers.countBackfillPending(false, false);
        long reelsOff = influencers.countReelsDue(future, false, false);
        long seedOff = influencers.countByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                InfluencerStatus.QUALIFIED, false, false);
        long noPkOff = influencers.countByStatusAndBeautyTrueAndSimilarProcessedAtIsNullAndIgUserIdIsNull(
                InfluencerStatus.QUALIFIED, false, false);

        Influencer fnbInf = influencers.save(qualified("count_fnb"));
        fnbInf.classifyFnb(CategoryClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        fnbInf.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(fnbInf);

        assertThat(influencers.countBackfillPending(false, false)).isEqualTo(backfillOff);
        assertThat(influencers.countBackfillPending(true, false)).isEqualTo(backfillOff + 1);
        assertThat(influencers.countReelsDue(future, false, false)).isEqualTo(reelsOff);
        assertThat(influencers.countReelsDue(future, true, false)).isEqualTo(reelsOff + 1);
        assertThat(influencers.countByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                InfluencerStatus.QUALIFIED, false, false)).isEqualTo(seedOff);
        assertThat(influencers.countByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                InfluencerStatus.QUALIFIED, true, false)).isEqualTo(seedOff + 1);
        assertThat(influencers.countByStatusAndBeautyTrueAndSimilarProcessedAtIsNullAndIgUserIdIsNull(
                InfluencerStatus.QUALIFIED, false, false)).isEqualTo(noPkOff);
        assertThat(influencers.countByStatusAndBeautyTrueAndSimilarProcessedAtIsNullAndIgUserIdIsNull(
                InfluencerStatus.QUALIFIED, true, false)).isEqualTo(noPkOff + 1);
    }

    @Test
    void 추적_대기_카운트와_릴스_선정도_토글을_따른다() {
        Instant future = Instant.now().plusSeconds(3600);
        long trackOff = influencers.countTrackDue(future, false, false);

        Influencer fnbInf = influencers.save(qualified("track_fnb"));
        fnbInf.classifyFnb(CategoryClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        fnbInf.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        fnbInf.setFirstCollectedAt(Instant.now().minusSeconds(86400));
        fnbInf.setLastCollectedAt(Instant.now().minusSeconds(86400));
        influencers.save(fnbInf);

        assertThat(influencers.countTrackDue(future, false, false)).isEqualTo(trackOff);
        assertThat(influencers.countTrackDue(future, true, false)).isEqualTo(trackOff + 1);

        assertThat(influencers.findReelsTargets(future, false, false, PageRequest.of(0, 1000)))
                .extracting(Influencer::getUsername).doesNotContain(PREFIX + "track_fnb");
        assertThat(influencers.findReelsTargets(future, true, false, PageRequest.of(0, 1000)))
                .extracting(Influencer::getUsername).contains(PREFIX + "track_fnb");
    }

    @Test
    void 수집_선정은_홈리빙_토글_on일_때만_홈리빙_인플루언서를_포함하고_회사는_항상_제외한다() {
        Influencer hlInf = influencers.save(qualified("gate_hl"));
        hlInf.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        hlInf.classifyHomeLiving(CategoryClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(hlInf);
        Influencer hlCo = influencers.save(qualified("gate_hl_co"));
        hlCo.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        hlCo.classifyHomeLiving(CategoryClass.COMPANY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(hlCo);

        Instant future = Instant.now().plusSeconds(3600);
        var off = influencers.findCollectTargets(future, false, false, PageRequest.of(0, 1000));
        assertThat(off).extracting(Influencer::getUsername)
                .doesNotContain(PREFIX + "gate_hl", PREFIX + "gate_hl_co");

        var on = influencers.findCollectTargets(future, false, true, PageRequest.of(0, 1000));
        assertThat(on).extracting(Influencer::getUsername).contains(PREFIX + "gate_hl");
        assertThat(on).extracting(Influencer::getUsername).doesNotContain(PREFIX + "gate_hl_co");

        // 릴스·시드 선정도 같은 게이트를 따른다
        assertThat(influencers.findReelsTargets(future, false, false, PageRequest.of(0, 1000)))
                .extracting(Influencer::getUsername).doesNotContain(PREFIX + "gate_hl");
        assertThat(influencers.findReelsTargets(future, false, true, PageRequest.of(0, 1000)))
                .extracting(Influencer::getUsername).contains(PREFIX + "gate_hl");
        assertThat(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                InfluencerStatus.QUALIFIED, false, false, PageRequest.of(0, 1000)))
                .extracting(Influencer::getUsername).doesNotContain(PREFIX + "gate_hl");
        assertThat(influencers.findByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                InfluencerStatus.QUALIFIED, false, true, PageRequest.of(0, 1000)))
                .extracting(Influencer::getUsername).contains(PREFIX + "gate_hl");
    }

    @Test
    void 카운트_쿼리도_홈리빙_토글에_따라_모수를_더한다() {
        Instant future = Instant.now().plusSeconds(3600);
        long backfillOff = influencers.countBackfillPending(false, false);
        long reelsOff = influencers.countReelsDue(future, false, false);
        long seedOff = influencers.countByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                InfluencerStatus.QUALIFIED, false, false);

        Influencer hlInf = influencers.save(qualified("count_hl"));
        hlInf.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        hlInf.classifyHomeLiving(CategoryClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(hlInf);

        assertThat(influencers.countBackfillPending(false, false)).isEqualTo(backfillOff);
        assertThat(influencers.countBackfillPending(false, true)).isEqualTo(backfillOff + 1);
        assertThat(influencers.countReelsDue(future, false, false)).isEqualTo(reelsOff);
        assertThat(influencers.countReelsDue(future, false, true)).isEqualTo(reelsOff + 1);
        assertThat(influencers.countByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                InfluencerStatus.QUALIFIED, false, false)).isEqualTo(seedOff);
        assertThat(influencers.countByStatusAndBeautyTrueAndSimilarProcessedAtIsNull(
                InfluencerStatus.QUALIFIED, false, true)).isEqualTo(seedOff + 1);
    }

    @Test
    void 홈리빙_판정이_저장되고_재조회된다() {
        Influencer inf = influencers.save(new Influencer(PREFIX + "hl-roundtrip"));
        inf.classifyHomeLiving(CategoryClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "집꾸미기 계정", "CAPTION");
        inf.setHomeLivingJudgedAt(Instant.parse("2026-08-27T00:00:00Z"));
        inf.setHomeLivingCaptionCount((short) 5);
        influencers.save(inf);

        Influencer found = influencers.findByUsername(PREFIX + "hl-roundtrip").orElseThrow();
        assertThat(found.getHomeLivingClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(found.getHomeLiving()).isTrue();
        assertThat(found.getHomeLivingCompany()).isFalse();
        assertThat(found.getHomeLivingSource()).isEqualTo(Influencer.BEAUTY_SOURCE_CLAUDE);
        assertThat(found.getHomeLivingReason()).isEqualTo("집꾸미기 계정");
        assertThat(found.getHomeLivingBasis()).isEqualTo("CAPTION");
        assertThat(found.getHomeLivingJudgedAt()).isEqualTo(Instant.parse("2026-08-27T00:00:00Z"));
        assertThat(found.getHomeLivingCaptionCount()).isEqualTo((short) 5);
    }

    @Test
    void 홈리빙_백필은_뷰티_판정_완료이고_홈리빙_미판정인_계정만_id순으로_고른다() {
        Influencer judged = influencers.save(qualified("hlbf_judged"));    // beauty 판정됨, 홈/리빙 NULL → 대상
        judged.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(judged);
        influencers.save(qualified("hlbf_unjudged"));                      // beauty NULL → 신규 경로 몫, 제외
        Influencer done = influencers.save(qualified("hlbf_done"));        // 홈/리빙 판정 완료 → 제외
        done.classify(BeautyClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        done.classifyHomeLiving(CategoryClass.NONE, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(done);
        // fnb 판정 여부는 조건이 아니다 — fnb 미판정이어도 홈/리빙 백필 모수에 든다
        // (선정 순서상 F&B 백필이 먼저 집지만, 쿼리 자체는 홈/리빙 축만 본다)
        Influencer fnbNull = influencers.save(qualified("hlbf_fnb_null"));
        fnbNull.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(fnbNull);

        var picked = influencers.findHomeLivingBackfillTargets(
                InfluencerStatus.QUALIFIED, PageRequest.of(0, 10));

        assertThat(picked).extracting(Influencer::getUsername)
                .filteredOn(u -> u.startsWith(PREFIX))
                .containsExactly(PREFIX + "hlbf_judged", PREFIX + "hlbf_fnb_null");
    }

    @Test
    void 홈리빙_백필_잔여_카운트는_선정_쿼리와_같은_모수를_센다() {
        long before = influencers.countHomeLivingBackfillRemaining(InfluencerStatus.QUALIFIED);

        Influencer judged = influencers.save(qualified("hlcnt_judged"));   // 백필 모수 → +1
        judged.classify(BeautyClass.NOT_BEAUTY, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(judged);
        influencers.save(qualified("hlcnt_unjudged"));                     // beauty NULL → 신규 경로 몫
        Influencer done = influencers.save(qualified("hlcnt_done"));       // 홈/리빙 판정 완료 → 제외
        done.classify(BeautyClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        done.classifyHomeLiving(CategoryClass.NONE, Influencer.BEAUTY_SOURCE_CLAUDE, "r", null);
        influencers.save(done);

        assertThat(influencers.countHomeLivingBackfillRemaining(InfluencerStatus.QUALIFIED))
                .isEqualTo(before + 1);
    }

    @Test
    void 홈리빙_분류_밖의_값은_DB가_거부한다() {
        Influencer inf = influencers.save(new Influencer(PREFIX + "hl-bad-class"));

        assertThatThrownBy(() -> jdbc.update(
                "update influencer set home_living_class = 'FURNITURE' where id = ?", inf.getId()))
                .hasMessageContaining("influencer_home_living_class_check");
        assertThatThrownBy(() -> jdbc.update(
                "update influencer set home_living_basis = 'VIBES' where id = ?", inf.getId()))
                .hasMessageContaining("influencer_home_living_basis_check");
    }

    @Test
    void fnb_분류_밖의_값은_DB가_거부한다() {
        // beauty 축과 같은 CHECK 제약(V18·V22 관용구) — enum 밖 문자열이 조용히 적재되지 않는다.
        Influencer inf = influencers.save(new Influencer(PREFIX + "fnb-bad-class"));

        assertThatThrownBy(() -> jdbc.update(
                "update influencer set fnb_class = 'RESTAURANT' where id = ?", inf.getId()))
                .hasMessageContaining("influencer_fnb_class_check");
        assertThatThrownBy(() -> jdbc.update(
                "update influencer set fnb_basis = 'VIBES' where id = ?", inf.getId()))
                .hasMessageContaining("influencer_fnb_basis_check");
    }

}
