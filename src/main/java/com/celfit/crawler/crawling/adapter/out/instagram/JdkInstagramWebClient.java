package com.celfit.crawler.crawling.adapter.out.instagram;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JdkInstagramWebClient implements InstagramWebClient {

    private final HttpClient client = HttpClient.newBuilder().build();
    private final DirectCommentProperties props;

    public JdkInstagramWebClient(DirectCommentProperties props) {
        this.props = props;
    }

    @Override
    public Response get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .timeout(props.requestTimeout())
                .header("User-Agent", UA)
                .GET().build());
    }

    @Override
    public Response post(String url, String formBody, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(props.requestTimeout())
                .header("User-Agent", UA)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody));
        headers.forEach(b::header);
        return send(b.build());
    }

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private Response send(HttpRequest req) {
        try {
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            String cookie = res.headers().firstValue("set-cookie").orElse("");
            return new Response(res.statusCode(), res.body(), Map.of("set-cookie", cookie));
        } catch (Exception e) {
            throw new ApifyException("인스타 요청 실패: " + e.getMessage(), e);
        }
    }
}
