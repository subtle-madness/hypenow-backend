package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.application.port.out.InfluencerDiscoveryRepository;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.BeautyClass;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerDiscovery;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 수동 발굴 등록 — 신규만 생성·기존 불변·정규화·형식 검증. */
class ManualDiscoveryServiceTest {

    static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");

    InfluencerRepository influencers = mock(InfluencerRepository.class);
    InfluencerDiscoveryRepository discoveries = mock(InfluencerDiscoveryRepository.class);
    ManualDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new ManualDiscoveryService(influencers, discoveries, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 신규_username은_DISCOVERED로_생성하고_수동_출처를_기록한다() {
        when(influencers.findByUsername("new.user")).thenReturn(Optional.empty());
        when(influencers.save(any())).thenAnswer(inv -> {
            Influencer i = inv.getArgument(0);
            i.setId(11L);
            return i;
        });

        var result = service.register("new.user");

        assertThat(result.created()).isTrue();
        assertThat(result.username()).isEqualTo("new.user");
        assertThat(result.status()).isEqualTo(InfluencerStatus.DISCOVERED);
        assertThat(result.beautyClass()).isNull();

        var captor = ArgumentCaptor.forClass(InfluencerDiscovery.class);
        verify(discoveries).save(captor.capture());
        InfluencerDiscovery d = captor.getValue();
        assertThat(d.getInfluencerId()).isEqualTo(11L);
        assertThat(d.getKeyword()).isEqualTo("수동:크롬");
        assertThat(d.getDiscoveredPostShortCode()).isNull();
        assertThat(d.getDiscoveredAt()).isEqualTo(NOW);
    }

    @Test
    void 기존_username은_아무것도_바꾸지_않고_현재_상태만_돌려준다() {
        Influencer existing = new Influencer("known.user");
        existing.setId(7L);
        existing.setStatus(InfluencerStatus.QUALIFIED);
        existing.classify(BeautyClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "근거", null);
        when(influencers.findByUsername("known.user")).thenReturn(Optional.of(existing));

        var result = service.register("known.user");

        assertThat(result.created()).isFalse();
        assertThat(result.status()).isEqualTo(InfluencerStatus.QUALIFIED);
        assertThat(result.beautyClass()).isEqualTo(BeautyClass.INFLUENCER);
        verify(influencers, never()).save(any());
        verify(discoveries, never()).save(any());
    }

    @Test
    void 공백과_앳을_벗기고_소문자로_정규화한다() {
        when(influencers.findByUsername("some.user")).thenReturn(Optional.empty());
        when(influencers.save(any())).thenAnswer(inv -> {
            Influencer i = inv.getArgument(0);
            i.setId(1L);
            return i;
        });

        var result = service.register("  @Some.User ");

        assertThat(result.username()).isEqualTo("some.user");
    }

    @Test
    void 형식_불량_username은_IllegalArgumentException이고_아무것도_저장하지_않는다() {
        assertThatThrownBy(() -> service.register("no way!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register(null)).isInstanceOf(IllegalArgumentException.class);
        verify(influencers, never()).save(any());
        verify(discoveries, never()).save(any());
    }
}
