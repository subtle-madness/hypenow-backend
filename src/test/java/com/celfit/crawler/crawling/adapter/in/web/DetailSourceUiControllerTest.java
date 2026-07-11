package com.celfit.crawler.crawling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.settings.application.service.DetailSourceSetting;
import com.celfit.crawler.settings.application.service.ProfileSourceSettingTest;
import com.celfit.crawler.settings.domain.DetailSource;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class DetailSourceUiControllerTest {

    @Test void 폼_저장이_타입별_소스를_반영() {
        var store = new HashMap<String, String>();
        var setting = new DetailSourceSetting(ProfileSourceSettingTest.fakeRepo(store));
        var ctrl = new DetailSourceUiController(setting);
        String view = ctrl.update("actor", "actor");
        assertThat(view).isEqualTo("redirect:/ui/settings");
        assertThat(setting.sourceFor(ContentType.REELS)).isEqualTo(DetailSource.ACTOR);
        assertThat(setting.sourceFor(ContentType.FEED)).isEqualTo(DetailSource.ACTOR);
    }
}
