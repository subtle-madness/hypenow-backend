package com.celfit.crawler.content.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.service.MediaItemExtractor.MediaItem;
import com.celfit.crawler.crawling.domain.RawSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 캡션 upsert 규칙 검증 — 클래스에 @Transactional을 붙이지 않고 실 DB에 쓰고 @AfterEach로 정리한다
 * (CollectJobIntegrationTest와 같은 이유: managed 엔티티 상태가 프로덕션 detached 상황을 가리지 않게).
 * content_caption FK가 RESTRICT라 정리 순서는 caption → content → influencer.
 */
class ContentCaptionUpserterIntegrationTest extends IntegrationTest {

    private static final Instant OLD = Instant.parse("2026-07-20T00:00:00Z");
    private static final Instant NEW = Instant.parse("2026-07-29T00:00:00Z");

    @Autowired ContentCaptionUpserter upserter;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("delete from content_caption");
        jdbc.update("delete from content");
        jdbc.update("delete from influencer");
    }

    private void seedContent(String shortCode) {
        jdbc.update("insert into influencer(username) values (?) on conflict do nothing", "owner");
        Long influencerId = jdbc.queryForObject(
                "select id from influencer where username='owner'", Long.class);
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

    private MediaItem item(String shortCode, String caption) {
        return new MediaItem(shortCode, OLD, ContentType.FEED, false, caption);
    }

    @Test
    void 캡션을_신규_적재한다() {
        seedContent("SC1");

        int n = upserter.upsert(List.of(item("SC1", "첫 캡션")), RawSource.SELF_GQL, OLD);

        assertThat(n).isEqualTo(1);
        assertThat(captionOf("SC1")).isEqualTo("첫 캡션");
    }

    @Test
    void 캡션이_없는_게시물도_빈_문자열로_행을_남긴다() {
        seedContent("SC2");

        upserter.upsert(List.of(item("SC2", "")), RawSource.HIKER_V1_MEDIAS, OLD);

        assertThat(captionOf("SC2")).isEqualTo("");
    }

    @Test
    void 더_최신_captured_at이_기존_캡션을_덮는다() {
        seedContent("SC3");
        upserter.upsert(List.of(item("SC3", "옛 캡션")), RawSource.SELF_GQL, OLD);

        upserter.upsert(List.of(item("SC3", "새 캡션")), RawSource.HIKER_V1_MEDIAS, NEW);

        assertThat(captionOf("SC3")).isEqualTo("새 캡션");
        assertThat(jdbc.queryForObject("select source from content_caption", String.class))
                .isEqualTo("HIKER_V1_MEDIAS");
    }

    /** 백필은 과거 페이지를 훑으므로, 라이브가 이미 최신 캡션을 넣었다면 되돌리지 않아야 한다. */
    @Test
    void 더_오래된_captured_at은_기존_캡션을_덮지_않는다() {
        seedContent("SC4");
        upserter.upsert(List.of(item("SC4", "최신 캡션")), RawSource.SELF_GQL, NEW);

        upserter.upsert(List.of(item("SC4", "옛 캡션")), RawSource.HIKER_V1_MEDIAS, OLD);

        assertThat(captionOf("SC4")).isEqualTo("최신 캡션");
    }

    /**
     * 미확인(null) 캡션은 배치에서 제외되므로, captured_at이 더 최신이어도 기존 캡션을 덮지
     * 않는다 — 이게 이 수정의 핵심 산출물이다. 라이브가 now()를 쓰므로 항상 백필을 이기는데,
     * 라이브가 새 페이지 형태를 못 읽어 null을 만들면(옛 구현은 이걸 ""로 바꿔 넣었다) 백필로
     * 건진 실제 캡션이 영구 소실될 뻔한 시나리오를 재현한다.
     */
    @Test
    void 미확인_null_캡션은_captured_at이_더_최신이어도_기존_캡션을_덮지_않는다() {
        seedContent("SC9");
        upserter.upsert(List.of(item("SC9", "백필로_건진_실제_캡션")), RawSource.HIKER_V1_MEDIAS, OLD);

        int n = upserter.upsert(List.of(item("SC9", null)), RawSource.SELF_GQL, NEW);

        assertThat(n).isZero();
        assertThat(captionOf("SC9")).isEqualTo("백필로_건진_실제_캡션");
        assertThat(jdbc.queryForObject("select source from content_caption", String.class))
                .isEqualTo("HIKER_V1_MEDIAS");
    }

    /** content 행이 없는 short_code는 FK 위반으로 터지지 말고 조용히 건너뛴다. */
    @Test
    void content_행이_없는_short_code는_건너뛴다() {
        seedContent("SC5");

        int n = upserter.upsert(
                List.of(item("SC5", "있음"), item("MISSING", "없음")), RawSource.SELF_GQL, OLD);

        assertThat(n).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from content_caption", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void 빈_컬렉션은_아무것도_하지_않는다() {
        assertThat(upserter.upsert(List.of(), RawSource.SELF_GQL, OLD)).isZero();
    }

    @Test
    void 여러_content를_한_번에_적재한다() {
        seedContent("SC6");
        seedContent("SC7");

        int n = upserter.upsert(
                List.of(item("SC6", "A"), item("SC7", "B")), RawSource.SELF_GQL, OLD);

        assertThat(n).isEqualTo(2);
        assertThat(captionOf("SC6")).isEqualTo("A");
        assertThat(captionOf("SC7")).isEqualTo("B");
    }

    /** Javadoc이 명시하는 계약 — dedup이 사라지면 결과가 드라이버 배치 재작성 설정에 의존하게 된다. */
    @Test
    void 배치_안_중복_short_code는_마지막_것만_반영된다() {
        seedContent("SC8");

        int n = upserter.upsert(
                List.of(item("SC8", "옛"), item("SC8", "새")), RawSource.SELF_GQL, OLD);

        assertThat(n).isEqualTo(1);
        assertThat(captionOf("SC8")).isEqualTo("새");
    }
}
