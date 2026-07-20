package com.celfit.crawler.crawling.adapter.out.instagram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.ArgumentMatchers;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.celfit.crawler.settings.application.service.ProxySourceSetting;
import com.celfit.crawler.settings.domain.ProxySource;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 로테이팅 프록시가 요청마다 새 exit IP를 배정하려면 매 요청이 새 커넥션이어야 한다. 풀링된 클라이언트를
 * 재사용하면 CONNECT 터널이 유지돼 exit IP가 고정되고 IG 익명 한도(~20회)에서 막힌다(실측). 그래서
 * 프록시 경로는 요청마다 클라이언트를 새로 만들고, 직접 경로는 공유 클라이언트를 재사용해야 한다.
 */
class JdkInstagramWebClientRotationTest {

    private HttpResponse<String> ok() throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<String> r = mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(200);
        when(r.body()).thenReturn("{}");
        when(r.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        return r;
    }

    private JdkInstagramWebClient.HttpClientFactory recordingFactory(List<String> creations,
                                                                     List<HttpClient> clients) {
        return url -> {
            creations.add(url);                // null=직접, 그 외=프록시 URL
            HttpClient c = mock(HttpClient.class);
            try {
                HttpResponse<String> resp = ok();   // 중첩 스터빙 방지 — 응답을 먼저 완성한다
                when(c.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                        .thenReturn(resp);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            clients.add(c);
            return c;
        };
    }

    @Test
    void 프록시_경로는_요청마다_새_클라이언트를_연다() throws Exception {
        List<String> creations = new ArrayList<>();
        List<HttpClient> clients = new ArrayList<>();
        ProxySourceSetting setting = mock(ProxySourceSetting.class);
        when(setting.current()).thenReturn(ProxySource.DATAIMPULSE_RESIDENTIAL);
        ProxyProperties props = new ProxyProperties(
                null, "http://u:p@gw.dataimpulse.com:823", null, Duration.ofSeconds(15));

        JdkInstagramWebClient web =
                new JdkInstagramWebClient(props, setting, recordingFactory(creations, clients));
        int afterInit = creations.size();

        web.get("https://x/1");
        web.get("https://x/2");
        web.get("https://x/3");

        // 프록시 요청 3번 → 새 클라이언트 3개, 모두 프록시 URL로 생성
        assertThat(creations.subList(afterInit, creations.size()))
                .containsExactly("http://u:p@gw.dataimpulse.com:823",
                        "http://u:p@gw.dataimpulse.com:823",
                        "http://u:p@gw.dataimpulse.com:823");
    }

    @Test
    void 직접_경로는_공유_클라이언트를_재사용한다() throws Exception {
        List<String> creations = new ArrayList<>();
        List<HttpClient> clients = new ArrayList<>();
        ProxySourceSetting setting = mock(ProxySourceSetting.class);
        when(setting.current()).thenReturn(ProxySource.DIRECT);
        ProxyProperties props = new ProxyProperties(null, null, null, Duration.ofSeconds(15));

        JdkInstagramWebClient web =
                new JdkInstagramWebClient(props, setting, recordingFactory(creations, clients));
        HttpClient direct = clients.get(0);   // 생성자에서 만든 공유 directClient
        int afterInit = creations.size();

        web.get("https://x/1");
        web.get("https://x/2");
        web.get("https://x/3");

        // 직접 경로는 새 클라이언트를 만들지 않고 공유 클라이언트로 3번 모두 전송
        assertThat(creations.size() - afterInit).isZero();
        verify(direct, times(3)).send(any(), any());
    }
}
