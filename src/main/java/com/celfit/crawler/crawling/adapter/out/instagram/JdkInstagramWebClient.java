package com.celfit.crawler.crawling.adapter.out.instagram;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.InstagramWebClient;
import com.celfit.crawler.settings.application.service.ProxySourceSetting;
import com.celfit.crawler.settings.domain.ProxySource;
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
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 인스타 자체크롤 HTTP. 프록시 경로는 런타임 설정(proxy.source)이 고른다 — 소스별로 HttpClient를 미리
 * 하나씩 만들어두고(프록시+자격증명 고정) 요청마다 활성 소스의 클라이언트를 쓴다. DIRECT나 URL 미설정
 * 소스는 프록시 없는 직접 클라이언트로 폴백한다.
 */
@Component
public class JdkInstagramWebClient implements InstagramWebClient {

    private static final Logger log = LoggerFactory.getLogger(JdkInstagramWebClient.class);

    static {
        // Instagram is served over HTTPS, so the proxy connection is a CONNECT tunnel.
        // The JDK disables Basic auth for proxy CONNECT tunneling by default (see
        // jdk.http.auth.tunneling.disabledSchemes), which would silently drop our
        // proxy credentials and make every request go out unauthenticated (and fail).
        // Clear it so HttpClient's Authenticator is actually used for the CONNECT handshake.
        if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        }
    }

    private final HttpClient directClient;
    private final Map<ProxySource, HttpClient> proxied = new EnumMap<>(ProxySource.class);
    private final ProxySourceSetting proxySource;
    private final Duration requestTimeout;

    public JdkInstagramWebClient(ProxyProperties proxyProps, ProxySourceSetting proxySource) {
        this.proxySource = proxySource;
        this.requestTimeout = proxyProps.requestTimeout() == null
                ? Duration.ofSeconds(15) : proxyProps.requestTimeout();
        this.directClient = newClient(null);
        // 소스별 클라이언트 구성 — URL 미설정이면 스킵(직접 폴백). 한 소스의 형식 오류가 앱 부팅이나
        // 다른 소스를 막지 않도록 소스별로 격리한다.
        for (ProxySource source : ProxySource.values()) {
            String url = proxyProps.urlFor(source);
            if (url == null) continue;
            try {
                proxied.put(source, newClient(url));
            } catch (RuntimeException e) {
                log.warn("프록시 {} 구성 실패 — 이 소스는 직접 연결로 폴백: {}", source, e.getMessage());
            }
        }
    }

    /** proxyUrl==null이면 프록시 없는 클라이언트. */
    private HttpClient newClient(String proxyUrl) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                // HTTP/1.1 고정 — 기본 HTTP/2는 커넥션 1개에 스트림을 다중화하는데, 프록시가
                // 응답을 중간에 끊으면(TLS BUFFER_UNDERFLOW) 스트림이 누수되고, 한도가 차면
                // 이후 모든 요청이 "too many concurrent streams"로 즉시 실패한다(실측).
                .version(HttpClient.Version.HTTP_1_1)
                // 익명 세션 쿠키가 후속 요청까지 이어지도록 공유 쿠키 저장소.
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL));

        if (proxyUrl != null) {
            URI proxyUri = URI.create(proxyUrl);
            String proxyHost = proxyUri.getHost();
            if (proxyHost == null || proxyHost.isBlank() || proxyUri.getPort() == -1) {
                throw new IllegalStateException("프록시 URL 형식 오류 — host:port 포함 필요 "
                        + "(예: http://user:pass@host:port): " + proxyUrl);
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
        return builder.build();
    }

    /** 현재 활성 프록시 소스의 클라이언트(미구성이면 직접). */
    private HttpClient activeClient() {
        return proxied.getOrDefault(proxySource.current(), directClient);
    }

    @Override
    public Response get(String url) {
        // x-ig-app-id: 로그아웃 GET(web_profile_info 등) 다수가 이 헤더 없이는 200을 내려주지 않는다
        // (실측 확인). 기존 댓글 플로우(포스트 페이지 GET)는 헤더가 추가돼도 영향 없다.
        return send(HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("User-Agent", UA)
                .header("x-ig-app-id", APP_ID)
                .GET().build());
    }

    @Override
    public Response post(String url, String formBody, Map<String, String> headers) {
        // Sec-Fetch-Site: same-origin 이 필수 — 없으면 Instagram 안티봇이 요청을 브라우저
        // 네비게이션으로 판정해 GraphQL JSON 대신 HTML 셸(200)을 돌려준다(실측 확인).
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
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
            HttpResponse<String> res = activeClient().send(req, HttpResponse.BodyHandlers.ofString());
            String cookie = res.headers().firstValue("set-cookie").orElse("");
            return new Response(res.statusCode(), res.body(), Map.of("set-cookie", cookie));
        } catch (Exception e) {
            if (isInterceptedServerUnauthorized(e)) return new Response(401, "", Map.of());
            throw new ApifyException("인스타 요청 실패: " + e.getMessage(), e);
        }
    }

    /**
     * Authenticator(프록시 자격증명)가 등록된 JDK HttpClient는 서버 401에 WWW-Authenticate
     * 헤더가 없으면 응답 대신 IOException을 던진다. 인스타그램의 401은 챌린지 헤더가 없으므로
     * 항상 이 경로로 온다 — 호출자가 계정·게시물 단위로 스킵할 수 있게 401 응답으로 복원한다.
     */
    static boolean isInterceptedServerUnauthorized(Exception e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("WWW-Authenticate header missing for response code 401");
    }
}
