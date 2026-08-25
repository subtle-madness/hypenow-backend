package com.celfit.crawler.crawling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.BeautyClass;
import com.celfit.crawler.crawling.domain.CategoryClass;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import java.util.List;
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

        String view = controller.override(1L, BeautyClass.BEAUTY_SERVICE, 2, null, null, null,
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

        controller.override(1L, BeautyClass.COMPANY, 0, null, null, null,
                new RedirectAttributesModelMap());

        assertThat(inf.getBeautyClass()).isEqualTo(BeautyClass.COMPANY);
        assertThat(inf.getBeauty()).isTrue();
        assertThat(inf.getBeautyCompany()).isTrue();
        assertThat(inf.getBeautySource()).isEqualTo(Influencer.BEAUTY_SOURCE_MANUAL);
    }

    @Test
    void 없는_인플루언서는_404() {
        when(influencers.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.override(9L, BeautyClass.INFLUENCER, 0, null, null, null,
                new RedirectAttributesModelMap()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void fnb_수동_오버라이드는_MANUAL_출처로_저장된다() {
        Influencer inf = new Influencer("cafe");
        when(influencers.findById(1L)).thenReturn(Optional.of(inf));

        String view = controller.overrideFnb(1L, CategoryClass.SERVICE, 0, null, null, null,
                new RedirectAttributesModelMap());

        assertThat(inf.getFnbClass()).isEqualTo(CategoryClass.SERVICE);
        assertThat(inf.getFnb()).isFalse();      // SERVICE는 카테고리 밖 — 파생 boolean false
        assertThat(inf.getFnbCompany()).isFalse();
        assertThat(inf.getFnbSource()).isEqualTo(Influencer.BEAUTY_SOURCE_MANUAL);
        assertThat(inf.getFnbReason()).isEqualTo("수동 판정");
        assertThat(view).isEqualTo("redirect:/ui/influencers");
    }

    @Test
    void fnb_수동_오버라이드는_뷰티_축을_건드리지_않는다() {
        // 2축은 독립 — F&B 오버라이드가 기존 뷰티 판정을 덮으면 안 된다.
        Influencer inf = new Influencer("dual");
        inf.classify(BeautyClass.INFLUENCER, Influencer.BEAUTY_SOURCE_CLAUDE, "뷰티 계정", null);
        when(influencers.findById(1L)).thenReturn(Optional.of(inf));

        controller.overrideFnb(1L, CategoryClass.INFLUENCER, 0, null, null, null,
                new RedirectAttributesModelMap());

        assertThat(inf.getFnbClass()).isEqualTo(CategoryClass.INFLUENCER);
        assertThat(inf.getFnb()).isTrue();
        assertThat(inf.getBeautyClass()).isEqualTo(BeautyClass.INFLUENCER);
        assertThat(inf.getBeautySource()).isEqualTo(Influencer.BEAUTY_SOURCE_CLAUDE);
    }

    @Test
    void fnb_오버라이드는_명단_필터를_리다이렉트에_보존한다() {
        Influencer inf = new Influencer("keep-filters");
        when(influencers.findById(1L)).thenReturn(Optional.of(inf));
        RedirectAttributesModelMap ra = new RedirectAttributesModelMap();

        controller.overrideFnb(1L, CategoryClass.COMPANY, 3,
                List.of(InfluencerStatus.QUALIFIED), List.of("INFLUENCER"), List.of("UNJUDGED"), ra);

        // RedirectAttributesModelMap은 값을 문자열로 포맷해 담는다(쿼리 파라미터로 나갈 형태)
        assertThat(ra.get("page")).hasToString("3");
        assertThat(ra.get("status")).hasToString("[QUALIFIED]");
        assertThat(ra.get("beauty")).hasToString("[INFLUENCER]");
        assertThat(ra.get("fnb")).hasToString("[UNJUDGED]");
    }

    @Test
    void 뷰티_오버라이드도_fnb_필터를_보존한다() {
        Influencer inf = new Influencer("keep-fnb-filter");
        when(influencers.findById(1L)).thenReturn(Optional.of(inf));
        RedirectAttributesModelMap ra = new RedirectAttributesModelMap();

        controller.override(1L, BeautyClass.INFLUENCER, 1, null, null, List.of("SERVICE"), ra);

        assertThat(ra.get("fnb")).hasToString("[SERVICE]");
    }

    @Test
    void fnb_오버라이드도_없는_인플루언서는_404() {
        when(influencers.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.overrideFnb(9L, CategoryClass.INFLUENCER, 0, null, null,
                null, new RedirectAttributesModelMap()))
                .isInstanceOf(ResponseStatusException.class);
    }
}
