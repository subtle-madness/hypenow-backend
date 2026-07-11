package com.celfit.crawler.crawling.adapter.out.hiker;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class JdkHikerHttp implements HikerHttp {

    private final HttpClient client = HttpClient.newHttpClient();
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;

    public JdkHikerHttp(HikerProperties props) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException("HIKER_API_KEY가 설정되지 않았습니다 (환경변수 필요)");
        }
        this.baseUrl = props.baseUrl() == null ? "https://api.hikerapi.com" : props.baseUrl();
        this.apiKey = props.apiKey();
        this.timeout = props.requestTimeout() == null ? Duration.ofSeconds(15) : props.requestTimeout();
    }

    @Override
    public String get(String path) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("x-access-key", apiKey)
                .header("accept", "application/json")
                .GET().build();
        try {
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                throw new ApifyException("Hiker HTTP " + res.statusCode() + ": " + res.body());
            }
            return res.body();
        } catch (IOException e) {
            throw new ApifyException("Hiker 요청 실패: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApifyException("Hiker 요청 중단", e);
        }
    }
}
