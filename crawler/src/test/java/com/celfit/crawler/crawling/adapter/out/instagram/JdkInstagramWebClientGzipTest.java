package com.celfit.crawler.crawling.adapter.out.instagram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.crawler.settings.application.service.ProxySourceSetting;
import com.celfit.crawler.settings.domain.ProxySource;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * 프록시는 터널을 지나는 바이트 그대로 과금한다. Accept-Encoding: gzip을 보내고 응답을 직접
 * 해제하면(JDK HttpClient는 자동 해제가 없다) 같은 요금으로 3~5배 많은 요청을 처리할 수 있다.
 */
class JdkInstagramWebClientGzipTest {

    private static byte[] gzip(String s) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }

    /** body 바이트와 응답 헤더를 지정한 응답을 돌려주는 페이크. 보낸 요청은 requests에 기록한다. */
    private JdkInstagramWebClient fake(byte[] body, Map<String, List<String>> headers,
                                       List<HttpRequest> requests) {
        ProxySourceSetting setting = mock(ProxySourceSetting.class);
        when(setting.current()).thenReturn(ProxySource.DIRECT);
        ProxyProperties props = new ProxyProperties(null, null, null, Duration.ofSeconds(15));
        JdkInstagramWebClient.HttpClientFactory factory = url -> {
            HttpClient c = mock(HttpClient.class);
            try {
                @SuppressWarnings("unchecked")
                HttpResponse<byte[]> resp = mock(HttpResponse.class);
                when(resp.statusCode()).thenReturn(200);
                when(resp.body()).thenReturn(body);
                when(resp.headers()).thenReturn(HttpHeaders.of(headers, (a, b) -> true));
                when(c.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                        .thenAnswer(inv -> {
                            requests.add(inv.getArgument(0));
                            return resp;
                        });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return c;
        };
        return new JdkInstagramWebClient(props, setting, factory);
    }

    @Test
    void 요청에_Accept_Encoding_gzip_헤더를_보낸다() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        JdkInstagramWebClient web = fake("{}".getBytes(StandardCharsets.UTF_8), Map.of(), requests);

        web.get("https://x/profile");
        web.post("https://x/graphql", "a=1", Map.of());

        assertThat(requests).hasSize(2).allSatisfy(req ->
                assertThat(req.headers().firstValue("Accept-Encoding")).hasValue("gzip"));
    }

    @Test
    void gzip_응답을_해제한다() throws Exception {
        String json = "{\"user\":\"celfit\",\"followers\":1234}";
        JdkInstagramWebClient web = fake(gzip(json),
                Map.of("Content-Encoding", List.of("gzip")), new ArrayList<>());

        assertThat(web.get("https://x/profile").body()).isEqualTo(json);
    }

    @Test
    void 무압축_응답은_그대로_통과한다() throws Exception {
        String json = "{\"plain\":true}";
        JdkInstagramWebClient web = fake(json.getBytes(StandardCharsets.UTF_8),
                Map.of(), new ArrayList<>());

        assertThat(web.get("https://x/profile").body()).isEqualTo(json);
    }
}
