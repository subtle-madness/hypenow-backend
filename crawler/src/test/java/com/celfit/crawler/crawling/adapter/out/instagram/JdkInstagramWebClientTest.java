package com.celfit.crawler.crawling.adapter.out.instagram;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class JdkInstagramWebClientTest {

    // 프록시 경로가 요청마다 새 클라이언트를 열고 즉시 닫으므로(스트림 누수 없음) HTTP/2를 쓴다.
    // 인스타 web_profile_info는 HTTP/1.1 요청을 봇으로 판정해 429를 주고, HTTP/2면 통과한다(실측).
    @Test
    void 생성한_클라이언트는_HTTP2를_쓴다() {
        assertThat(JdkInstagramWebClient.newClient(null).version())
                .isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(JdkInstagramWebClient.newClient("http://u:p@gw.dataimpulse.com:823").version())
                .isEqualTo(HttpClient.Version.HTTP_2);
    }

    // Authenticator(프록시 자격증명)가 등록된 JDK HttpClient는 서버 401에 WWW-Authenticate
    // 헤더가 없으면 응답을 돌려주지 않고 IOException을 던진다. 인스타그램의 401은 챌린지
    // 헤더가 없으므로 항상 이 경로로 온다 — 호출자가 계정 단위로 스킵할 수 있게 401 응답으로
    // 판별해 복원해야 한다.
    @Test
    void 프록시_인증기가_가로챈_서버_401_예외를_판별한다() {
        assertThat(JdkInstagramWebClient.isInterceptedServerUnauthorized(
                new IOException("WWW-Authenticate header missing for response code 401"))).isTrue();
    }

    @Test
    void 다른_예외는_401로_오인하지_않는다() {
        assertThat(JdkInstagramWebClient.isInterceptedServerUnauthorized(
                new IOException("connection reset"))).isFalse();
        assertThat(JdkInstagramWebClient.isInterceptedServerUnauthorized(
                new IOException((String) null))).isFalse();
        // 프록시 407 챌린지 부재는 프록시 설정 문제 — 서버 401과 다르게 취급
        assertThat(JdkInstagramWebClient.isInterceptedServerUnauthorized(
                new IOException("Proxy-Authenticate header missing for response code 407"))).isFalse();
    }
}
