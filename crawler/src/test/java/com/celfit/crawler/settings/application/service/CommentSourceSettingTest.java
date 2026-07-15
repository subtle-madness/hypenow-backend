package com.celfit.crawler.settings.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.settings.domain.CommentSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class CommentSourceSettingTest extends IntegrationTest {

    @Autowired CommentSourceSetting setting;

    @Test
    void 기본값은_ACTOR다() {
        assertThat(setting.current()).isEqualTo(CommentSource.ACTOR);
    }

    @Test
    void 업데이트하면_그_값을_돌려준다() {
        setting.update(CommentSource.DIRECT);
        assertThat(setting.current()).isEqualTo(CommentSource.DIRECT);
    }

    @Test
    void 알수없는_저장값은_ACTOR로_방어된다() {
        setting.updateRaw("garbage");
        assertThat(setting.current()).isEqualTo(CommentSource.ACTOR);
    }
}
