package com.celfit.crawler.crawling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.BeautyClass;
import com.celfit.crawler.crawling.domain.Influencer;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class InfluencerBeautyControllerTest {

    InfluencerRepository influencers = mock(InfluencerRepository.class);
    InfluencerBeautyController controller = new InfluencerBeautyController(influencers);

    @Test
    void 수동_판정은_beauty_class와_파생값과_MANUAL_출처를_기록한다() {
        Influencer inf = new Influencer("a");
        inf.classify(BeautyClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "이전 판정", null);
        when(influencers.findById(1L)).thenReturn(Optional.of(inf));

        String view = controller.override(1L, BeautyClass.BEAUTY_SERVICE, 2, null, null,
                new RedirectAttributesModelMap());

        assertThat(inf.getBeautyClass()).isEqualTo(BeautyClass.BEAUTY_SERVICE);
        assertThat(inf.getBeauty()).isFalse();
        assertThat(inf.getBeautyCompany()).isFalse();
        assertThat(inf.getBeautySource()).isEqualTo(Influencer.BEAUTY_SOURCE_MANUAL);
        assertThat(inf.getBeautyReason()).isEqualTo("수동 판정");
        assertThat(view).isEqualTo("redirect:/ui/influencers");
    }

    @Test
    void 수동으로_뷰티_회사로_판정할_수_있다() {
        Influencer inf = new Influencer("brand");
        when(influencers.findById(1L)).thenReturn(Optional.of(inf));

        controller.override(1L, BeautyClass.COMPANY, 0, null, null, new RedirectAttributesModelMap());

        assertThat(inf.getBeautyClass()).isEqualTo(BeautyClass.COMPANY);
        assertThat(inf.getBeauty()).isTrue();
        assertThat(inf.getBeautyCompany()).isTrue();
        assertThat(inf.getBeautySource()).isEqualTo(Influencer.BEAUTY_SOURCE_MANUAL);
    }

    @Test
    void 없는_인플루언서는_404() {
        when(influencers.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.override(9L, BeautyClass.INFLUENCER, 0, null, null,
                new RedirectAttributesModelMap()))
                .isInstanceOf(ResponseStatusException.class);
    }
}
