package com.celfit.crawler.apify;

import com.celfit.crawler.config.ApifyProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Apify 비동기 실행: run 시작 → 상태 폴링 → SUCCEEDED면 dataset 수신.
 * run-sync 엔드포인트는 장시간 실행에서 게이트웨이가 먼저 끊겨 과금+결과 유실 → 금지.
 * 폴링/dataset 조회가 일시 오류로 실패하면 예외로 즉시 포기하며 액터는 abort되지 않는다
 * — crawl_run FAILED 기록과 Apify 콘솔로 추적, 재시도는 잡 레벨 재실행으로 커버.
 */
@Component
public class ApifyClient implements ApifyRunner {

    private static final Set<String> TERMINAL_FAILURES = Set.of("FAILED", "ABORTED", "TIMED-OUT");

    private final ApifyHttp http;
    private final ApifyProperties props;
    private final ObjectMapper om;
    private final Sleeper sleeper;
    private final Clock clock;

    public ApifyClient(ApifyHttp http, ApifyProperties props, ObjectMapper om,
                       Sleeper sleeper, Clock clock) {
        this.http = http;
        this.props = props;
        this.om = om;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    @Override
    public ApifyResult run(String actorId, Map<String, Object> input) {
        JsonNode started = postJson(url("/v2/acts/" + actorId + "/runs"), input);
        String runId = started.path("data").path("id").asString();
        String datasetId = started.path("data").path("defaultDatasetId").asString();
        if (runId.isEmpty() || datasetId.isEmpty()) {
            throw new ApifyException("run 시작 응답에 id/datasetId 없음: " + started);
        }

        Instant deadline = clock.instant().plus(props.runTimeout());
        while (true) {
            String status = getJson(url("/v2/actor-runs/" + runId))
                    .path("data").path("status").asString();
            if ("SUCCEEDED".equals(status)) break;
            if (TERMINAL_FAILURES.contains(status)) {
                throw new ApifyException("run " + runId + " 종료 상태 " + status);
            }
            if (clock.instant().isAfter(deadline)) {
                ApifyException timeout = new ApifyException(
                        "run " + runId + " 타임아웃(" + props.runTimeout() + ") — abort 시도함");
                try {
                    postJson(url("/v2/actor-runs/" + runId + "/abort"), Map.of());
                } catch (RuntimeException e) {
                    timeout.addSuppressed(e);
                }
                throw timeout;
            }
            sleeper.sleep(props.pollInterval());
        }

        String body = http.get(url("/v2/datasets/" + datasetId + "/items") + "?clean=true&format=json");
        try {
            List<Map<String, Object>> items = om.readValue(body, new TypeReference<>() {});
            return new ApifyResult(runId, items);
        } catch (JacksonException e) {
            throw new ApifyException("dataset 파싱 실패: " + e.getMessage(), e);
        }
    }

    private String url(String path) {
        return props.baseUrl() + path;
    }

    private JsonNode getJson(String url) {
        return parse(http.get(url));
    }

    private JsonNode postJson(String url, Map<String, Object> body) {
        try {
            return parse(http.post(url, om.writeValueAsString(body)));
        } catch (JacksonException e) {
            throw new ApifyException("입력 직렬화 실패", e);
        }
    }

    private JsonNode parse(String body) {
        try {
            return om.readTree(body);
        } catch (JacksonException e) {
            throw new ApifyException("응답 파싱 실패: " + e.getMessage(), e);
        }
    }
}
