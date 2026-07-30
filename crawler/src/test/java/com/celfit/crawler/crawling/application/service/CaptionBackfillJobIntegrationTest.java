package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.crawling.domain.TriggerType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 백필이 저장된 raw 원형에서 캡션을 소급 적재하는지 — 특히 어떤 analytics 뷰도 읽지 않는
 * HIKER_V1_MEDIAS가 실제로 건져지는지가 이 잡의 존재 이유다.
 *
 * <p>IntegrationTest는 Postgres 컨테이너를 JVM 전체에서 공유한다. 이 클래스 자신이 심는
 * short_code·raw 행만 세도록 매 테스트 시작 시 raw_media_page·raw_profile을 비우고
 * 워터마크를 0으로 리셋한다 — 다른 테스트 클래스가 남긴 원형이 stats.pages()/captions()
 * 단정을 오염시키지 않게 하기 위함이다(단정을 느슨히 하는 대신 격리로 해결).
 */
class CaptionBackfillJobIntegrationTest extends IntegrationTest {

    @Autowired CaptionBackfillJob job;
    @Autowired JdbcTemplate jdbc;

    private Long influencerId;
    private Long runId;

    @BeforeEach
    void resetRawAndWatermark() {
        jdbc.update("delete from content_caption");
        jdbc.update("delete from content");
        jdbc.update("delete from raw_media_page");
        jdbc.update("delete from raw_profile");
        jdbc.update("delete from raw_run_item");
        jdbc.update("delete from crawl_run");
        jdbc.update("delete from influencer");
        jdbc.update("update app_setting set value='0' where key like 'caption.backfill.%'");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from content_caption");
        jdbc.update("delete from content");
        jdbc.update("delete from raw_media_page");
        jdbc.update("delete from raw_profile");
        jdbc.update("delete from raw_run_item");
        jdbc.update("delete from crawl_run");
        jdbc.update("delete from influencer");
        jdbc.update("update app_setting set value='0' where key like 'caption.backfill.%'");
    }

    private void seed() {
        jdbc.update("insert into influencer(username) values ('owner')");
        influencerId = jdbc.queryForObject(
                "select id from influencer where username='owner'", Long.class);
        jdbc.update("""
                insert into crawl_run(job, trigger_type, actor_id, status, started_at)
                values ('COLLECT', 'MANUAL', 'a', 'RUNNING', now())""");
        runId = jdbc.queryForObject("select max(id) from crawl_run", Long.class);
    }

    private void seedContent(String shortCode) {
        jdbc.update("""
                insert into content(short_code, content_type, owner_username, influencer_id,
                                    uploaded_at, status, first_seen_at, origin)
                values (?, 'FEED', 'owner', ?, now(), 'PENDING', now(), 'ENUMERATION')""",
                shortCode, influencerId);
    }

    private String captionOf(String shortCode) {
        return jdbc.queryForObject("""
                select cc.caption from content_caption cc
                join content c on c.id = cc.content_id where c.short_code = ?""",
                String.class, shortCode);
    }

    @Test
    void v1_medias_원형에서_캡션을_소급_적재한다() {
        seed();
        seedContent("V1CAP");
        jdbc.update("""
                insert into raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at)
                values (?, ?, 'HIKER_V1_MEDIAS', ?::jsonb, now())""", influencerId, runId,
                """
                {"medias":[{"code":"V1CAP","taken_at":1773630245,"caption_text":"뷰티 루틴 공유"}]}""");

        var stats = job.run(TriggerType.MANUAL);

        assertThat(captionOf("V1CAP")).isEqualTo("뷰티 루틴 공유");
        assertThat(stats.captions()).isEqualTo(1);
    }

    @Test
    void self_gql_프로필_내장_타임라인에서도_캡션을_적재한다() {
        seed();
        seedContent("SGCAP");
        jdbc.update("""
                insert into raw_profile(influencer_id, crawl_run_id, source, payload, captured_at)
                values (?, ?, 'SELF_GQL', ?::jsonb, now())""", influencerId, runId,
                """
                {"data":{"user":{"edge_owner_to_timeline_media":{"edges":[{"node":{
                "shortcode":"SGCAP","taken_at_timestamp":1773630245,
                "edge_media_to_caption":{"edges":[{"node":{"text":"오늘의 메이크업"}}]}}}]}}}}""");

        job.run(TriggerType.MANUAL);

        assertThat(captionOf("SGCAP")).isEqualTo("오늘의 메이크업");
    }

    /** 워터마크가 전진하므로 두 번째 실행은 같은 페이지를 다시 처리하지 않는다. */
    @Test
    void 재실행하면_워터마크_이후만_처리한다() {
        seed();
        seedContent("W1");
        jdbc.update("""
                insert into raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at)
                values (?, ?, 'HIKER_V1_MEDIAS', ?::jsonb, now())""", influencerId, runId,
                """
                {"medias":[{"code":"W1","taken_at":1773630245,"caption_text":"첫 실행"}]}""");
        job.run(TriggerType.MANUAL);

        var second = job.run(TriggerType.MANUAL);

        assertThat(second.pages()).isZero();
        assertThat(captionOf("W1")).isEqualTo("첫 실행");
    }

    @Test
    void 원형이_없으면_아무것도_하지_않는다() {
        seed();

        var stats = job.run(TriggerType.MANUAL);

        assertThat(stats.pages()).isZero();
        assertThat(stats.captions()).isZero();
    }
}
