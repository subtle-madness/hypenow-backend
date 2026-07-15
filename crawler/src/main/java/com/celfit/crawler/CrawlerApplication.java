package com.celfit.crawler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CrawlerApplication {
    public static void main(String[] args) {
        // 프록시 HTTPS CONNECT 터널의 Basic 인증 활성화. jdk.internal.net.http.common.Utils가
        // 이 값을 최초 1회만 캐싱하므로 어떤 HttpClient보다 먼저 — SpringApplication.run 전에 —
        // 설정해야 한다. (JdkInstagramWebClient의 static 블록만으로는 다른 HttpClient가 먼저
        // 초기화되면 기본값 "Basic"이 캐싱돼 프록시 인증이 꺼진다 → 407.)
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        SpringApplication.run(CrawlerApplication.class, args);
    }
}
