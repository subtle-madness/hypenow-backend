package com.celfit.crawler.crawling.adapter.out.instagram;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import java.net.Authenticator;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JdkInstagramWebClient implements InstagramWebClient {

    static {
        // Instagram is served over HTTPS, so the proxy connection is a CONNECT tunnel.
        // The JDK disables Basic auth for proxy CONNECT tunneling by default (see
        // jdk.http.auth.tunneling.disabledSchemes), which would silently drop our
        // Apify Proxy credentials and make every request go out unauthenticated (and fail).
        // Clear it so HttpClient's Authenticator is actually used for the CONNECT handshake.
        if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        }
    }

    private final HttpClient client;
    private final DirectCommentProperties props;

    public JdkInstagramWebClient(DirectCommentProperties props) {
        this.props = props;
        HttpClient.Builder builder = HttpClient.newBuilder()
                // Shared cookie store so anonymous session cookies set by the initial page
                // GET carry over to the subsequent /api/graphql POST on this same client.
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL));

        String proxyUrl = props.proxyUrl();
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            URI proxyUri = URI.create(proxyUrl);
            String proxyHost = proxyUri.getHost();
            if (proxyHost == null || proxyHost.isBlank() || proxyUri.getPort() == -1) {
                throw new IllegalStateException(
                        "crawler.direct-comment.proxy-url 형식 오류 — host:port 포함 필요 "
                        + "(예: http://user:pass@proxy.apify.com:8000): " + proxyUrl);
            }
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyUri.getPort())));

            String userInfo = proxyUri.getUserInfo();
            if (userInfo != null && userInfo.contains(":")) {
                String[] parts = userInfo.split(":", 2);
                String user = parts[0];
                char[] pass = parts[1].toCharArray();
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(user, pass);
                    }
                });
            }
        }

        this.client = builder.build();
    }

    @Override
    public Response get(String url) {
        // x-ig-app-id: 로그아웃 GET(web_profile_info 등) 다수가 이 헤더 없이는 200을 내려주지 않는다
        // (실측 확인). 기존 댓글 플로우(포스트 페이지 GET)는 헤더가 추가돼도 영향 없다.
        return send(HttpRequest.newBuilder(URI.create(url))
                .timeout(props.requestTimeout())
                .header("User-Agent", UA)
                .header("x-ig-app-id", APP_ID)
                .GET().build());
    }

    @Override
    public Response post(String url, String formBody, Map<String, String> headers) {
        // Sec-Fetch-Site: same-origin 이 필수 — 없으면 Instagram 안티봇이 요청을 브라우저
        // 네비게이션으로 판정해 GraphQL JSON 대신 HTML 셸(200)을 돌려준다(실측 확인).
        // 나머지 sec-fetch/Accept 는 실제 브라우저 XHR 모방(견고성).
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(props.requestTimeout())
                .header("User-Agent", UA)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "*/*")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .POST(HttpRequest.BodyPublishers.ofString(formBody));
        headers.forEach(b::header);
        return send(b.build());
    }

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final String APP_ID = "936619743392459";

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
