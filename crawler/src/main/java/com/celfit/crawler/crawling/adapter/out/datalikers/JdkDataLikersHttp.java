package com.celfit.crawler.crawling.adapter.out.datalikers;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DataLikers REST 전송 — x-access-key 헤더 인증, api.datalikers.com 기본 호스트.
 * 빈 등록은 {@code CrawlerConfig}가 한다({@link CountingDataLikersHttp}로 감싸 명시 조립).
 */
public class JdkDataLikersHttp implements DataLikersHttp {

    private static final Logger log = LoggerFactory.getLogger(JdkDataLikersHttp.class);

    private final HttpClient client = HttpClient.newHttpClient();
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;

    public JdkDataLikersHttp(DataLikersProperties props) {
        // 키가 없어도 앱은 부팅한다(기본 소스 SELF는 DataLikers를 쓰지 않음). 실제 호출 시 get()에서 검증.
        this.baseUrl = props.baseUrl() == null ? "https://api.datalikers.com" : props.baseUrl();
        this.apiKey = props.apiKey();
        this.timeout = props.requestTimeout() == null ? Duration.ofSeconds(15) : props.requestTimeout();
        if (this.apiKey == null || this.apiKey.isBlank()) {
            log.warn("DATALIKERS_API_KEY 미설정 — DataLikers 소스는 사용할 수 없습니다(다른 소스는 정상 동작).");
        }
    }

    @Override
    public String get(String path) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApifyException("DATALIKERS_API_KEY가 설정되지 않았습니다 — DataLikers 소스를 쓰려면 환경변수 필요");
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("x-access-key", apiKey)
                .header("accept", "application/json")
                .GET().build();
        try {
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                throw new ApifyException("DataLikers HTTP " + res.statusCode() + ": " + res.body());
            }
            return res.body();
        } catch (IOException e) {
            throw new ApifyException("DataLikers 요청 실패: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApifyException("DataLikers 요청 중단", e);
        }
    }
}
