package com.celfit.crawler.crawling.adapter.out.hiker;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.NotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JdkHikerHttp implements HikerHttp {

    private static final Logger log = LoggerFactory.getLogger(JdkHikerHttp.class);

    private final HttpClient client = HttpClient.newHttpClient();
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;

    public JdkHikerHttp(HikerProperties props) {
        // 키가 없어도 앱은 부팅한다(기본 소스 SELF는 HikerAPI를 쓰지 않음).
        // HikerAPI 소스/보충을 실제로 호출할 때만 get()에서 키를 검증한다.
        this.baseUrl = props.baseUrl() == null ? "https://api.hikerapi.com" : props.baseUrl();
        this.apiKey = props.apiKey();
        this.timeout = props.requestTimeout() == null ? Duration.ofSeconds(15) : props.requestTimeout();
        if (this.apiKey == null || this.apiKey.isBlank()) {
            log.warn("HIKER_API_KEY 미설정 — HikerAPI 소스/보충은 사용할 수 없습니다(SELF 등 다른 소스는 정상 동작).");
        }
    }

    @Override
    public String get(String path) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApifyException("HIKER_API_KEY가 설정되지 않았습니다 — HikerAPI 소스/보충을 쓰려면 환경변수 필요");
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("x-access-key", apiKey)
                .header("accept", "application/json")
                .GET().build();
        try {
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404) {
                // 대상 부재(계정 삭제·개명 등) — 재시도 무의미, 호출자가 소프트 딜리트 등 종결 처리
                throw new NotFoundException("Hiker HTTP 404: " + res.body());
            }
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
