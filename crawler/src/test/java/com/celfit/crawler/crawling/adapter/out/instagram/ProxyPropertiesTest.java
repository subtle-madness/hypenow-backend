package com.celfit.crawler.crawling.adapter.out.instagram;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.settings.domain.ProxySource;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProxyPropertiesTest {

    ProxyProperties props = new ProxyProperties(
            "http://u:p@proxy.apify.com:8000",
            "http://res_login:res_pw@gw.dataimpulse.com:823",
            "",                                   // 모바일 미설정
            Duration.ofSeconds(15));

    @Test void 소스별_프록시_URL을_돌려준다() {
        assertThat(props.urlFor(ProxySource.APIFY)).isEqualTo("http://u:p@proxy.apify.com:8000");
        assertThat(props.urlFor(ProxySource.DATAIMPULSE_RESIDENTIAL))
                .isEqualTo("http://res_login:res_pw@gw.dataimpulse.com:823");
    }

    @Test void DIRECT는_프록시_URL이_없다() {
        assertThat(props.urlFor(ProxySource.DIRECT)).isNull();
    }

    @Test void 미설정_소스는_null로_폴백된다() {
        // 모바일 URL을 안 넣어두면(빈칸) 그 소스를 골라도 프록시 없이(직접) 동작해야 한다.
        assertThat(props.urlFor(ProxySource.DATAIMPULSE_MOBILE)).isNull();
    }
}
