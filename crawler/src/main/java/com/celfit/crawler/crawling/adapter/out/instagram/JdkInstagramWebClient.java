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
import org.springframework.beans.factory.annotation.Autowired;
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

    /** HttpClient 생성 이음매 — 프로덕션은 {@link #newClient}, 테스트는 커넥션 생성을 세는 페이크를 주입. */
    @FunctionalInterface
    interface HttpClientFactory {
        /** proxyUrl==null이면 프록시 없는 클라이언트. */
        HttpClient create(String proxyUrl);
    }

    private final HttpClientFactory clientFactory;
    private final HttpClient directClient;
    private final Map<ProxySource, String> proxyUrls = new EnumMap<>(ProxySource.class);
    private final ProxySourceSetting proxySource;
    private final Duration requestTimeout;

    @Autowired
    public JdkInstagramWebClient(ProxyProperties proxyProps, ProxySourceSetting proxySource) {
        this(proxyProps, proxySource, JdkInstagramWebClient::newClient);
    }

    JdkInstagramWebClient(ProxyProperties proxyProps, ProxySourceSetting proxySource,
                          HttpClientFactory clientFactory) {
        this.clientFactory = clientFactory;
        this.proxySource = proxySource;
        this.requestTimeout = proxyProps.requestTimeout() == null
                ? Duration.ofSeconds(15) : proxyProps.requestTimeout();
        this.directClient = clientFactory.create(null);
        // 소스별 프록시 URL 등록 — URL 미설정이면 스킵(직접 폴백). 부팅 시 한 번 만들어보며 형식을
        // 검증해, 한 소스의 오류가 앱 부팅이나 다른 소스를 막지 않도록 소스별로 격리한다.
        for (ProxySource source : ProxySource.values()) {
            String url = proxyProps.urlFor(source);
            if (url == null) continue;
            try {
                clientFactory.create(url);   // 형식 검증(host:port 확인) — 소켓은 열지 않는다
                proxyUrls.put(source, url);
            } catch (RuntimeException e) {
                log.warn("프록시 {} 구성 실패 — 이 소스는 직접 연결로 폴백: {}", source, e.getMessage());
            }
        }
    }

    /** proxyUrl==null이면 프록시 없는 클라이언트. */
    static HttpClient newClient(String proxyUrl) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                // HTTP/2 고정 — 인스타 web_profile_info는 HTTP/1.1 요청을 봇으로 판정해 IP를
                // 아무리 돌려도 429를 주고, HTTP/2면 통과한다(실측). 예전엔 HTTP/2가 커넥션 1개에
                // 스트림을 다중화하다 프록시가 응답을 중간에 끊으면(TLS BUFFER_UNDERFLOW) 스트림이
                // 누수돼 "too many concurrent streams"로 막혔는데, 지금은 프록시 경로가 요청마다
                // 새 클라이언트를 열고 즉시 close하므로 커넥션당 요청이 1~2개뿐 — 누수가 쌓이지 않는다.
                .version(HttpClient.Version.HTTP_2)
                // 익명 세션 쿠키 저장소. 직접 경로는 공유 클라이언트라 요청 간 이어지고, 프록시 경로는
                // 요청마다 새 클라이언트(=새 exit IP)라 매번 새 쿠키로 시작한다 — IP가 바뀌므로 오히려 자연스럽다.
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
        String proxyUrl = proxyUrls.get(proxySource.current());
        if (proxyUrl == null) {
            // 직접 경로는 로테이션이 필요 없으니 공유 클라이언트를 재사용한다.
            return exchange(directClient, req);
        }
        // 프록시 경로는 요청마다 새 HttpClient(=새 CONNECT 터널)를 열어, 로테이팅 프록시가 매 요청
        // 새 exit IP를 배정하게 한다. 풀링된 클라이언트를 재사용하면 터널이 유지돼 exit IP가 배치 내내
        // 한 개로 고정되고, IG 익명 요청 한도(~20회)에서 401 연타로 막힌다(실측). 요청 후 close로 터널을
        // 즉시 닫아 다음 요청이 새 커넥션을 열도록 한다.
        try (HttpClient client = clientFactory.create(proxyUrl)) {
            return exchange(client, req);
        }
    }

    private Response exchange(HttpClient client, HttpRequest req) {
        try {
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
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
