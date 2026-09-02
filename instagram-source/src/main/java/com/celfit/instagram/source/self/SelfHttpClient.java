package com.celfit.instagram.source.self;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 자체크롤 저수준 HTTP(crawler JdkInstagramWebClient 이식, 순수 JDK). 프록시 경로는 요청마다 새
 * HttpClient(=새 CONNECT 터널=새 exit IP, K=1 로테이션), 종료는 shutdownNow(). HTTP/2 강제(IG는
 * HTTP/1.1 web_profile_info를 봇판정 429). gzip 수동 해제. IG의 401(WWW-Authenticate 부재)은
 * IOException으로 오는데, 이를 status 401로 복원해 호출자가 회복(로테이트·재시도)하게 한다.
 */
public class SelfHttpClient implements SelfTransport {

	private static final String UA =
			"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
			+ "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3); // fastfail(죽은 IP 꼬리 절단)

	/*
	 * 프록시 CONNECT 터널의 Basic auth를 JDK 기본이 끈다 — 클리어해야 자격증명이 실린다(crawler와 동일).
	 * 정본은 각 소비 모듈의 main()에서 SpringApplication.run 이전에 선설정하는 것(crawler
	 * CrawlerApplication, monitoring MonitoringApplication 참고) — jdk.internal.net.http.common.Utils가
	 * 이 프로퍼티를 최초 HttpClient 생성 시점에 1회만 캐싱하기 때문에, Spring 배선상 다른 HttpClient
	 * (예: JdkHikerHttp)가 SelfHttpClient보다 먼저 초기화되면 이 static 블록은 이미 캐싱된 뒤라
	 * 무효하다(실증됨: monitoring에서 407 전량 실패). 이 블록은 main()에서 미처 선설정하지 못한
	 * 다른 소비자를 위한 최선노력 보험일 뿐 — 새 소비 모듈을 추가할 때 이 블록만 믿지 말 것.
	 */
	static {
		if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
			System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
		}
	}

	private final ProxyConfig proxy;
	private final Duration requestTimeout;

	public SelfHttpClient(ProxyConfig proxy) {
		this.proxy = proxy;
		this.requestTimeout = proxy.requestTimeout() == null ? Duration.ofSeconds(15) : proxy.requestTimeout();
	}

	@Override
	public SelfResponse get(String url, ProxyTier tier, Map<String, String> headers) {
		HttpRequest.Builder b = baseRequest(url).GET();
		headers.forEach(b::header);
		return exchange(b.build(), tier);
	}

	@Override
	public SelfResponse post(String url, String formBody, ProxyTier tier, Map<String, String> headers) {
		HttpRequest.Builder b = baseRequest(url)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8));
		headers.forEach(b::header);
		return exchange(b.build(), tier);
	}

	private HttpRequest.Builder baseRequest(String url) {
		return HttpRequest.newBuilder(URI.create(url))
				.timeout(requestTimeout)
				.header("User-Agent", UA)
				.header("Accept-Encoding", "gzip");
	}

	private SelfResponse exchange(HttpRequest req, ProxyTier tier) {
		HttpClient client;
		try {
			client = newClient(proxy.urlFor(tier));
		} catch (RuntimeException e) {
			// 프록시 URL 파싱 실패(URI.create의 IllegalArgumentException, 포트 누락 시
			// InetSocketAddress의 IllegalArgumentException, ProxyUrls.withCountry의
			// StringIndexOutOfBoundsException 등) — 설정 오류라 재시도 무의미, OTHER로 분류한다.
			// 이 구간이 try 밖에 있으면 SelfCrawlException으로 안 싸이고 그대로 새어 폴백망을 우회한다(F2).
			throw new SelfCrawlException(SelfErrorClass.OTHER,
					"프록시 URL 설정 오류: " + e.getMessage(), e);
		}
		try {
			HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
			String enc = res.headers().firstValue("content-encoding").orElse("");
			String body = "gzip".equalsIgnoreCase(enc.trim())
					? gunzip(res.body())
					: new String(res.body(), StandardCharsets.UTF_8);
			return new SelfResponse(res.statusCode(), body, res.headers().map());
		} catch (Exception e) {
			if (isInterceptedUnauthorized(e)) {
				return new SelfResponse(401, "");
			}
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt(); // 실행자 주도 취소를 삼키지 않는다.
			}
			throw new SelfCrawlException(SelfErrorClassifier.ofException(e),
					"자체크롤 전송 실패: " + e.getMessage(), e);
		} finally {
			client.shutdownNow(); // 즉시 터널 종료(로테이션). close()는 401 뒤 수십초 블록.
		}
	}

	private static HttpClient newClient(String proxyUrl) {
		HttpClient.Builder b = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_2)
				.connectTimeout(CONNECT_TIMEOUT);
		if (proxyUrl != null) {
			URI p = URI.create(proxyUrl);
			b.proxy(ProxySelector.of(new InetSocketAddress(p.getHost(), p.getPort())));
			String ui = p.getUserInfo();
			if (ui != null && ui.contains(":")) {
				String[] parts = ui.split(":", 2);
				b.authenticator(new Authenticator() {
					@Override
					protected PasswordAuthentication getPasswordAuthentication() {
						return new PasswordAuthentication(parts[0], parts[1].toCharArray());
					}
				});
			}
		}
		return b.build();
	}

	private static String gunzip(byte[] compressed) throws IOException {
		try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
			return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** IG의 401은 WWW-Authenticate 부재라 JDK가 IOException을 던진다 — 그 메시지로 판별해 401로 복원. */
	static boolean isInterceptedUnauthorized(Exception e) {
		String m = e.getMessage();
		return m != null && m.contains("WWW-Authenticate header missing for response code 401");
	}
}
