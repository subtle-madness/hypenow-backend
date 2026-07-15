package com.celfit.crawler.crawling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class InfluencerBeautyControllerTest {

    InfluencerRepository influencers = mock(InfluencerRepository.class);
    InfluencerBeautyController controller = new InfluencerBeautyController(influencers);

    @Test
    void 수동_판정은_beauty와_MANUAL_출처를_기록한다() {
        Influencer inf = new Influencer("a");
        inf.setBeauty(true);
        inf.setBeautySource(Influencer.BEAUTY_SOURCE_CLAUDE);
        when(influencers.findById(1L)).thenReturn(Optional.of(inf));

        String view = controller.override(1L, false, 2, null, new RedirectAttributesModelMap());

        assertThat(inf.getBeauty()).isFalse();
        assertThat(inf.getBeautySource()).isEqualTo(Influencer.BEAUTY_SOURCE_MANUAL);
        assertThat(inf.getBeautyReason()).isEqualTo("수동 판정");
        assertThat(view).isEqualTo("redirect:/ui/influencers");
    }

    @Test
    void 없는_인플루언서는_404() {
        when(influencers.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.override(9L, true, 0, null, new RedirectAttributesModelMap()))
                .isInstanceOf(ResponseStatusException.class);
    }
}
