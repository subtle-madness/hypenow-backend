package com.celfit.crawler.apify;

import com.celfit.crawler.config.ApifyProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.stereotype.Component;

@Component
public class JdkApifyHttp implements ApifyHttp {

    private final HttpClient client = HttpClient.newHttpClient();
    private final String bearer;

    public JdkApifyHttp(ApifyProperties props) {
        if (props.token() == null || props.token().isBlank()) {
            throw new IllegalStateException("APIFY_TOKEN이 설정되지 않았습니다 (환경변수 필요)");
        }
        this.bearer = "Bearer " + props.token();
    }

    @Override
    public String get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", bearer)
                .GET().build());
    }

    @Override
    public String post(String url, String jsonBody) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build());
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                throw new ApifyException("Apify HTTP " + res.statusCode() + ": " + res.body());
            }
            return res.body();
        } catch (IOException e) {
            throw new ApifyException("Apify 요청 실패: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApifyException("Apify 요청 중단", e);
        }
    }
}
