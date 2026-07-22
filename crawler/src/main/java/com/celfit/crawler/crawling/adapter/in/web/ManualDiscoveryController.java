package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.crawling.application.service.ManualDiscoveryService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 수동 발굴 등록 API — 크롬 익스텐션 전용. Caddy가 이 경로만 외부에 공개하고(/crawler 프리픽스
 * strip), 사전 공유 토큰(X-Api-Token)으로 인증한다. 토큰 미설정이면 전체 비활성(fail-closed 503).
 */
@RestController
@RequestMapping("/api/manual-discoveries")
public class ManualDiscoveryController {

    public record Request(String username) {}
    public record Response(String username, boolean created, String status, String beautyClass) {}

    private final ManualDiscoveryService service;
    private final String token;

    public ManualDiscoveryController(ManualDiscoveryService service,
                                     @Value("${crawler.manual-discovery.token:}") String token) {
        this.service = service;
        this.token = token;
    }

    @PostMapping
    public ResponseEntity<?> register(
            @RequestHeader(value = "X-Api-Token", required = false) String apiToken,
            @RequestBody Request request) {
        if (token == null || token.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "manual-discovery 토큰 미설정"));
        }
        if (!token.equals(apiToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "토큰 불일치"));
        }
        try {
            var r = service.register(request.username());
            return ResponseEntity.ok(new Response(r.username(), r.created(),
                    r.status().name(), r.beautyClass() == null ? null : r.beautyClass().name()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
