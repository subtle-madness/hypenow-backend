package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.application.port.out.SearchKeywordRepository;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.content.domain.SearchKeyword;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.InfluencerDiscoveryRepository;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawDiscoveryPostRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerDiscovery;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.RawDiscoveryPost;
import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DiscoverJobTest {

    static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    SearchKeywordRepository keywords = mock(SearchKeywordRepository.class);
    InfluencerRepository influencers = mock(InfluencerRepository.class);
    InfluencerDiscoveryRepository discoveries = mock(InfluencerDiscoveryRepository.class);
    ContentRepository contents = mock(ContentRepository.class);
    RawDiscoveryPostRepository rawDiscovery = mock(RawDiscoveryPostRepository.class);
    DiscoverSourceSelector selector = mock(DiscoverSourceSelector.class);

    DiscoverJob job = new DiscoverJob(keywords, influencers, discoveries, contents,
            rawDiscovery, selector, CLOCK);

    AtomicLong influencerIds = new AtomicLong(0);
    AtomicLong contentIds = new AtomicLong(0);

    @BeforeEach
    void wireSaveIdAssignment() {
        when(influencers.save(any(Influencer.class))).thenAnswer(inv -> {
            Influencer i = inv.getArgument(0);
            i.setId(influencerIds.incrementAndGet());
            return i;
        });
        when(contents.save(any(Content.class))).thenAnswer(inv -> {
            Content c = inv.getArgument(0);
            c.setId(contentIds.incrementAndGet());
            return c;
        });
        when(selector.currentSource()).thenReturn(RawSource.HIKER_HASHTAG);
    }

    static SearchKeyword keyword(String text) {
        return new SearchKeyword(text, NOW);
    }

    static Map<String, Object> item(String shortCode, String productType, String owner, String caption) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shortCode", shortCode);
        m.put("timestamp", "2026-07-01T12:00:00.000Z");
        m.put("ownerUsername", owner);
        if (productType != null) m.put("productType", productType);
        if (caption != null) m.put("caption", caption);
        return m;
    }

    @Test
    void 활성_키워드만_순회한다() {
        when(keywords.findByEnabledTrue()).thenReturn(List.of(keyword("립"), keyword("틴트")));
        when(selector.fetch(anyString(), eq(TriggerType.MANUAL)))
                .thenReturn(new CrawlExecutor.Execution(1L, List.of()));

        job.run(TriggerType.MANUAL);

        verify(selector).fetch("립", TriggerType.MANUAL);
        verify(selector).fetch("틴트", TriggerType.MANUAL);
        verify(selector, times(2)).fetch(anyString(), eq(TriggerType.MANUAL));
    }

    @Test
    void 새_작성자와_기존_작성자_모두_influencer_discovery_행이_추가된다() {
        when(keywords.findByEnabledTrue()).thenReturn(List.of(keyword("립")));
        when(selector.fetch(eq("립"), eq(TriggerType.MANUAL))).thenReturn(
                new CrawlExecutor.Execution(11L, List.of(
                        item("sc1", "clips", "kim", "새 립스틱"),
                        item("sc2", null, "lee", null))));
        when(influencers.findByUsername("kim")).thenReturn(Optional.empty());
        Influencer existingLee = new Influencer("lee");
        existingLee.setId(999L);
        when(influencers.findByUsername("lee")).thenReturn(Optional.of(existingLee));
        when(contents.findByShortCode(anyString())).thenReturn(Optional.empty());

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.newInfluencers()).isEqualTo(1);
        assertThat(summary.knownInfluencers()).isEqualTo(1);

        ArgumentCaptor<Influencer> savedInf = ArgumentCaptor.forClass(Influencer.class);
        verify(influencers).save(savedInf.capture());
        assertThat(savedInf.getValue().getUsername()).isEqualTo("kim");
        assertThat(savedInf.getValue().getStatus()).isEqualTo(InfluencerStatus.DISCOVERED);

        ArgumentCaptor<InfluencerDiscovery> savedDisc = ArgumentCaptor.forClass(InfluencerDiscovery.class);
        verify(discoveries, times(2)).save(savedDisc.capture());
        List<InfluencerDiscovery> discs = savedDisc.getAllValues();
        assertThat(discs).extracting(InfluencerDiscovery::getKeyword).containsOnly("립");
        assertThat(discs).extracting(InfluencerDiscovery::getDiscoveredPostShortCode)
                .containsExactlyInAnyOrder("sc1", "sc2");
        assertThat(discs).extracting(InfluencerDiscovery::getDiscoveredAt).containsOnly(NOW);
        // kim(신규)의 influencer_id는 방금 저장된 값, lee(기존)는 999
        assertThat(discs).extracting(InfluencerDiscovery::getInfluencerId)
                .containsExactlyInAnyOrder(1L, 999L);
    }

    @Test
    void 발굴_게시물은_content_upsert된다_shortCode_dedup_influencer_id_연결() {
        when(keywords.findByEnabledTrue()).thenReturn(List.of(keyword("립")));
        when(selector.fetch(eq("립"), eq(TriggerType.MANUAL))).thenReturn(
                new CrawlExecutor.Execution(11L, List.of(item("sc1", "clips", "kim", null))));
        when(influencers.findByUsername("kim")).thenReturn(Optional.empty());
        when(contents.findByShortCode("sc1")).thenReturn(Optional.empty());

        job.run(TriggerType.MANUAL);

        ArgumentCaptor<Content> savedContent = ArgumentCaptor.forClass(Content.class);
        verify(contents).save(savedContent.capture());
        Content c = savedContent.getValue();
        assertThat(c.getShortCode()).isEqualTo("sc1");
        assertThat(c.getContentType()).isEqualTo(ContentType.REELS);
        assertThat(c.getOwnerUsername()).isEqualTo("kim");
        assertThat(c.getInfluencerId()).isEqualTo(1L);
        assertThat(c.getFirstSeenAt()).isEqualTo(NOW);
    }

    @Test
    void 이미_존재하는_content는_다시_저장하지_않고_기존_id로_raw를_연결한다() {
        when(keywords.findByEnabledTrue()).thenReturn(List.of(keyword("립")));
        when(selector.fetch(eq("립"), eq(TriggerType.MANUAL))).thenReturn(
                new CrawlExecutor.Execution(11L, List.of(item("sc1", "clips", "kim", null))));
        when(influencers.findByUsername("kim")).thenReturn(Optional.empty());
        Content existing = new Content("sc1", ContentType.REELS, "kim", 1L, NOW, NOW);
        existing.setId(555L);
        when(contents.findByShortCode("sc1")).thenReturn(Optional.of(existing));

        job.run(TriggerType.MANUAL);

        verify(contents, never()).save(any(Content.class));
        ArgumentCaptor<RawDiscoveryPost> savedRaw = ArgumentCaptor.forClass(RawDiscoveryPost.class);
        verify(rawDiscovery).save(savedRaw.capture());
        assertThat(savedRaw.getValue().getContentId()).isEqualTo(555L);
    }

    @Test
    void raw는_항상_저장되고_source가_찍히며_shortCode_caption_세터가_채워진다() {
        when(keywords.findByEnabledTrue()).thenReturn(List.of(keyword("립")));
        when(selector.fetch(eq("립"), eq(TriggerType.MANUAL))).thenReturn(
                new CrawlExecutor.Execution(77L, List.of(item("sc1", "clips", "kim", "예쁜 립스틱"))));
        when(influencers.findByUsername("kim")).thenReturn(Optional.empty());
        when(contents.findByShortCode("sc1")).thenReturn(Optional.empty());
        when(selector.currentSource()).thenReturn(RawSource.APIFY_ACTOR);

        job.run(TriggerType.MANUAL);

        ArgumentCaptor<RawDiscoveryPost> savedRaw = ArgumentCaptor.forClass(RawDiscoveryPost.class);
        verify(rawDiscovery).save(savedRaw.capture());
        RawDiscoveryPost raw = savedRaw.getValue();
        assertThat(raw.getCrawlRunId()).isEqualTo(77L);
        assertThat(raw.getSource()).isEqualTo(RawSource.APIFY_ACTOR);
        assertThat(raw.getShortCode()).isEqualTo("sc1");
        assertThat(raw.getCaption()).isEqualTo("예쁜 립스틱");
        assertThat(raw.getPayload()).containsEntry("shortCode", "sc1");
        assertThat(raw.getCapturedAt()).isEqualTo(NOW);
    }

    @Test
    void 키워드_하나_실패해도_다음_키워드는_계속_처리된다() {
        when(keywords.findByEnabledTrue()).thenReturn(List.of(keyword("립"), keyword("틴트")));
        when(selector.fetch(eq("립"), eq(TriggerType.MANUAL))).thenThrow(new ApifyException("액터 폭발"));
        when(selector.fetch(eq("틴트"), eq(TriggerType.MANUAL))).thenReturn(
                new CrawlExecutor.Execution(2L, List.of(item("sc9", "clips", "park", null))));
        when(influencers.findByUsername("park")).thenReturn(Optional.empty());
        when(contents.findByShortCode("sc9")).thenReturn(Optional.empty());

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.failedKeywords()).isEqualTo(1);
        assertThat(summary.newInfluencers()).isEqualTo(1);
        verify(rawDiscovery, times(1)).save(any(RawDiscoveryPost.class));
    }
}
