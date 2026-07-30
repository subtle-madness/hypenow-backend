package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.content.application.service.ContentCaptionUpserter;
import com.celfit.crawler.crawling.application.port.out.RawMediaPageRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 백필이 저장된 raw 원형에서 캡션을 소급 적재하는지 - 특히 어떤 analytics 뷰도 읽지 않는
 * HIKER_V1_MEDIAS가 실제로 건져지는지가 이 잡의 존재 이유다.
 *
 * <p>IntegrationTest는 Postgres 컨테이너를 JVM 전체에서 공유한다. 이 클래스 자신이 심는
 * short_code, raw 행만 세도록 매 테스트 시작 시 raw_media_page, raw_profile을 비우고
 * 워터마크를 0으로 리셋한다 - 다른 테스트 클래스가 남긴 원형이 stats.pages()/captions()
 * 단정을 오염시키지 않게 하기 위함이다(단정을 느슨히 하는 대신 격리로 해결).
 */
class CaptionBackfillJobIntegrationTest extends IntegrationTest {

    @Autowired CaptionBackfillJob job;
    @Autowired JdbcTemplate jdbc;

    /** 중지 후 재개 테스트용 — stopFlag만 다른 CaptionBackfillJob 인스턴스를 수동 조립하는 데 쓴다. */
    @Autowired RawMediaPageRepository mediaPages;
    @Autowired RawProfileRepository profiles;
    @Autowired AppSettingRepository settings;
    @Autowired TransactionTemplate txTemplate;

    /**
     * 스파이 - 진짜 SQL 제약 위반을 raw 원형만으로 재현할 방법을 먼저 찾아봤다. 캡션 문자열에
     * 제어 문자(널 문자) 하나를 심어 Postgres 인코딩 오류를 유도하는 안을 시도했으나, 그런
     * 이스케이프 시퀀스를 담은 JSON 문자열은 raw_media_page를 심는 시딩 단계의 jsonb 캐스팅
     * 자체가 이미 거부한다(psql로 직접 확인: 지원하지 않는 유니코드 이스케이프라는 이유로
     * 캐스트가 실패한다). 즉 이 코드베이스의 파싱 계약(캡션 non-null, content 존재 확인 후에만
     * 배치 포함, 배치 내 dedup)이 정상 흐름에서 그런 상태 자체를 만들지 못하게 막고 있어, 순수
     * 통합 테스트만으로는 진짜 SQL 레벨 실패를 재현할 방법이 없었다. 대신 실제 실행 지점
     * (ContentCaptionUpserter.upsert)에서 실제 SQL 예외와 같은 타입
     * (DataIntegrityViolationException)을 던지도록 스파이를 스텁해, "쓰기 트랜잭션이 예외를
     * 삼키지 않는지"를 검증한다 - 예외가 발생하는 위치, 타입, 전파 경로는 실제 SQL 실패와 같고,
     * 다른 것은 그 예외의 근본 원인(제약 위반 vs 테스트 스텁)뿐이다.
     */
    @MockitoSpyBean ContentCaptionUpserter captionUpserter;

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

    private long watermark(String key) {
        return Long.parseLong(jdbc.queryForObject(
                "select value from app_setting where key = ?", String.class, key));
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

    /**
     * 회귀 테스트 - 청크 안 한 페이지의 쓰기가 SQL 레벨로 실패하면, 그 청크에서 앞서 이미 성공한
     * 캡션까지 함께 롤백되고(부분 커밋 없음) 워터마크도 전진하지 않아 재실행이 청크 전체를
     * 다시 처리해야 한다. 이 불변식이 깨지면(예: 쓰기 트랜잭션 콜백 안에서 다시 예외를 삼키는
     * 회귀가 들어오면) 이 테스트가 실패한다: 예외가 삼켜지면 job.run()이 정상 반환하고,
     * OK1 캡션이 커밋된 채로 남고, 워터마크가 전진해버린다.
     */
    @Test
    void 청크_안_쓰기_실패는_그_청크_전체를_커밋하지_않고_워터마크도_전진시키지_않는다() {
        seed();
        seedContent("OK1");
        seedContent("BAD1");
        jdbc.update("""
                insert into raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at)
                values (?, ?, 'HIKER_V1_MEDIAS', ?::jsonb, now())""", influencerId, runId,
                """
                {"medias":[{"code":"OK1","taken_at":1773630245,"caption_text":"정상"}]}""");
        jdbc.update("""
                insert into raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at)
                values (?, ?, 'HIKER_V1_MEDIAS', ?::jsonb, now())""", influencerId, runId,
                """
                {"medias":[{"code":"BAD1","taken_at":1773630245,"caption_text":"불량"}]}""");

        // OK1이 담긴 페이지 호출은 실제 로직을 그대로 타게 두고(실제로 INSERT를 수행한다),
        // BAD1이 담긴 페이지 호출에서만 진짜 SQL 예외와 같은 타입을 던지도록 스텁한다.
        Mockito.doThrow(new DataIntegrityViolationException("시뮬레이션된 SQL 제약 위반"))
                .when(captionUpserter)
                .upsert(argThat(items -> items != null
                                && items.stream().anyMatch(it -> "BAD1".equals(it.shortCode()))),
                        any(), any());

        assertThatThrownBy(() -> job.run(TriggerType.MANUAL))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 같은 청크, 같은 트랜잭션이라 앞서 성공했던 OK1 캡션까지 함께 롤백된다.
        assertThat(jdbc.queryForObject("select count(*) from content_caption", Integer.class))
                .isZero();
        // 워터마크가 전진하지 않아 재실행이 이 청크를 통째로 다시 처리한다.
        assertThat(watermark(CaptionBackfillJob.MEDIA_WATERMARK)).isZero();
    }

    /**
     * 회귀 테스트 - "DB 재조회 커서 루프"는 이 잡이 이 코드베이스에 처음 도입한 패턴이다(다른 잡은
     * 후보를 한 쿼리로 메모리에 다 가져와 청크한다). PAGE_CHUNK(200)건 이하로만 심으면 청크
     * 루프가 단 한 번만 돌아 커서 전진 로직이 전혀 실행되지 않으므로, PAGE_CHUNK보다 많은 행을
     * 심어 청크가 최소 2번 돌게 한다.
     *
     * <p>검증 완료 — `long last = chunk.get(chunk.size() - 1).getId();`를 `chunk.get(0).getId();`로
     * 바꿔 커서가 청크의 마지막이 아니라 첫 id로 전진하도록 일부러 깨뜨린 뒤 이 테스트만 단독
     * 실행해봤다: cursor가 반복마다 1씩만 전진해 같은 행을 계속 재처리하면서 `stats.pages()`가
     * 기대값(205)을 한참 초과했다 — FAILED로 확인 후 원복했다.
     */
    @Test
    void 청크_경계를_넘겨도_커서가_이어지고_전건_캡션이_적재된다() {
        seed();
        int total = CaptionBackfillJob.PAGE_CHUNK + 5;
        jdbc.update("""
                insert into content(short_code, content_type, owner_username, influencer_id,
                                    uploaded_at, status, first_seen_at, origin)
                select 'C' || g, 'FEED', 'owner', ?, now(), 'PENDING', now(), 'ENUMERATION'
                from generate_series(1, ?) g""", influencerId, total);
        jdbc.update("""
                insert into raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at)
                select ?, ?, 'HIKER_V1_MEDIAS',
                       format('{"medias":[{"code":"C%s","taken_at":1773630245,"caption_text":"c%s"}]}',
                              g, g)::jsonb,
                       now()
                from generate_series(1, ?) g""", influencerId, runId, total);
        Long lastId = jdbc.queryForObject("select max(id) from raw_media_page", Long.class);

        var stats = job.run(TriggerType.MANUAL);

        assertThat(stats.pages()).isEqualTo(total);
        assertThat(stats.captions()).isEqualTo(total);
        assertThat(jdbc.queryForObject("select count(*) from content_caption", Integer.class))
                .isEqualTo(total);
        assertThat(captionOf("C1")).isEqualTo("c1");
        assertThat(captionOf("C" + total)).isEqualTo("c" + total);
        // 커서가 정확히 마지막으로 심은 행까지 전진했는지 — 이 불변식이 이 테스트의 핵심이다.
        assertThat(watermark(CaptionBackfillJob.MEDIA_WATERMARK)).isEqualTo(lastId);
    }

    /**
     * 회귀 테스트 - stopFlag는 협조적 중지다: 첫 청크를 끝까지 처리하고 커밋한 뒤, 다음 청크를
     * 시작하기 전에 확인해서 멈춘다. 검증할 것: (a) 두 번째 청크가 처리되지 않는다(pages ==
     * PAGE_CHUNK), (b) 워터마크가 첫 청크의 마지막 id까지만 전진한다, (c) 그 상태에서 재실행하면
     * 나머지를 이어서 처리한다.
     *
     * <p>실제 JobStopFlag는 다른 스레드가 비동기로 request()를 호출하는 구조라, 단일 스레드
     * 테스트에서 "첫 청크 처리 후"라는 타이밍을 흉내내려면 스텁이 필요하다 — 첫 번째 확인
     * (첫 청크 진입 전)은 통과시키고 그 다음부터는 중지가 걸린 것처럼 구는 JobStopFlag 서브클래스로
     * CaptionBackfillJob을 수동 조립한다(다른 협력자는 실제 Spring 빈을 그대로 쓴다). 이렇게
     * 조립한 인스턴스는 이 테스트 안에서만 쓰고 버리므로, 공유되는 실제 JobStopFlag 빈 상태는
     * 건드리지 않는다.
     */
    @Test
    void 중지_요청_후_남은_청크는_처리하지_않고_재실행하면_이어서_처리한다() {
        seed();
        int total = CaptionBackfillJob.PAGE_CHUNK + 5;
        jdbc.update("""
                insert into content(short_code, content_type, owner_username, influencer_id,
                                    uploaded_at, status, first_seen_at, origin)
                select 'S' || g, 'FEED', 'owner', ?, now(), 'PENDING', now(), 'ENUMERATION'
                from generate_series(1, ?) g""", influencerId, total);
        jdbc.update("""
                insert into raw_media_page(influencer_id, crawl_run_id, source, payload, captured_at)
                select ?, ?, 'HIKER_V1_MEDIAS',
                       format('{"medias":[{"code":"S%s","taken_at":1773630245,"caption_text":"s%s"}]}',
                              g, g)::jsonb,
                       now()
                from generate_series(1, ?) g""", influencerId, runId, total);
        Long firstChunkLastId = jdbc.queryForObject(
                "select id from raw_media_page order by id limit 1 offset ?",
                Long.class, CaptionBackfillJob.PAGE_CHUNK - 1);

        var stoppingJob = new CaptionBackfillJob(mediaPages, profiles, captionUpserter, settings,
                txTemplate, new OneShotAfterFirstChunkStopFlag());

        var stopped = stoppingJob.run(TriggerType.MANUAL);

        assertThat(stopped.pages()).isEqualTo(CaptionBackfillJob.PAGE_CHUNK);
        assertThat(stopped.captions()).isEqualTo(CaptionBackfillJob.PAGE_CHUNK);
        assertThat(watermark(CaptionBackfillJob.MEDIA_WATERMARK)).isEqualTo(firstChunkLastId);

        // 재실행 — 실제 stopFlag 빈은 요청된 적이 없으므로 정상 완주해 나머지를 이어서 처리한다.
        var resumed = job.run(TriggerType.MANUAL);

        assertThat(resumed.pages()).isEqualTo(total - CaptionBackfillJob.PAGE_CHUNK);
        assertThat(jdbc.queryForObject("select count(*) from content_caption", Integer.class))
                .isEqualTo(total);
    }

    /**
     * 첫 확인(첫 청크 진입 전)은 중지 미요청으로 통과시키고, 그 다음부터는 중지가 걸린 것처럼
     * 군다 — "한 청크를 처리한 뒤 다른 스레드가 중지를 요청했다"는 타이밍을 단일 스레드
     * 테스트에서 결정적으로 재현하기 위한 스텁이다.
     */
    private static final class OneShotAfterFirstChunkStopFlag extends JobStopFlag {
        private final AtomicInteger checks = new AtomicInteger();

        @Override
        public boolean isRequested(JobName job) {
            return checks.getAndIncrement() > 0;
        }
    }
}
