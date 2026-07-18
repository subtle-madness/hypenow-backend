package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentOrigin;
import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.domain.Influencer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * upsert는 방문당 게시물 12개를 다루므로 DB 왕복이 방문 지연의 주범이었다(개별 조회 12회 + 개별 저장) —
 * 일괄 조회 1회 + 일괄 저장 1회로 줄이되, 신규 생성·DISCOVERY 승격 규칙은 그대로 유지한다.
 */
class ContentUpserterTest {

    static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");
    static final Instant TAKEN = Instant.parse("2026-07-10T00:00:00Z");

    ContentRepository contents = mock(ContentRepository.class);
    ContentUpserter upserter = new ContentUpserter(contents, Clock.fixed(NOW, ZoneOffset.UTC));

    Influencer inf;

    @BeforeEach
    void setUp() {
        inf = new Influencer("beauty_user");
        inf.setId(7L);
        when(contents.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    static MediaItemExtractor.MediaItem item(String shortCode) {
        return new MediaItemExtractor.MediaItem(shortCode, TAKEN, ContentType.FEED, false);
    }

    static Content discovery(String shortCode) {
        return new Content(shortCode, ContentType.FEED, "beauty_user", 7L, TAKEN, TAKEN, ContentOrigin.DISCOVERY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 일괄_조회_1회와_일괄_저장_1회로_upsert한다() {
        when(contents.findByShortCodeIn(any())).thenReturn(List.of(discovery("b")));

        int n = upserter.upsert(List.of(item("a"), item("b"), item("c")), inf);

        assertThat(n).isEqualTo(3);
        verify(contents, never()).findByShortCode(anyString());   // 개별 조회 없음
        verify(contents, never()).save(any(Content.class));       // 개별 저장 없음
        ArgumentCaptor<List<Content>> saved = ArgumentCaptor.forClass(List.class);
        verify(contents).saveAll(saved.capture());                 // 신규만 일괄 저장
        assertThat(saved.getValue()).extracting(Content::getShortCode).containsExactly("a", "c");
    }

    @Test
    void 신규는_ENUMERATION으로_생성되고_기존_DISCOVERY는_승격된다() {
        Content existing = discovery("b");
        when(contents.findByShortCodeIn(any())).thenReturn(List.of(existing));

        upserter.upsert(List.of(item("a"), item("b")), inf);

        assertThat(existing.getOrigin()).isEqualTo(ContentOrigin.ENUMERATION);  // 승격(관리 엔티티 — 커밋 시 반영)
    }

    @Test
    void 기존_ENUMERATION은_건드리지_않는다() {
        Content existing = discovery("b");
        existing.setOrigin(ContentOrigin.ENUMERATION);
        when(contents.findByShortCodeIn(any())).thenReturn(List.of(existing));

        int n = upserter.upsert(List.of(item("b")), inf);

        assertThat(n).isEqualTo(1);
        assertThat(existing.getOrigin()).isEqualTo(ContentOrigin.ENUMERATION);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 중복_shortCode_아이템은_한_번만_생성한다() {
        // 응답에 같은 게시물이 두 번 오면(고정 게시물 등) 일괄 insert에서 유니크 제약이 터지면 안 된다
        when(contents.findByShortCodeIn(any())).thenReturn(List.of());

        upserter.upsert(List.of(item("a"), item("a")), inf);

        ArgumentCaptor<List<Content>> saved = ArgumentCaptor.forClass(List.class);
        verify(contents).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
    }

    @Test
    void 빈_입력은_DB를_건드리지_않는다() {
        int n = upserter.upsert(List.of(), inf);

        assertThat(n).isZero();
        verify(contents, never()).findByShortCodeIn(any());
        verify(contents, never()).saveAll(any());
    }
}
