package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.crawling.application.port.in.TriggerJobUseCase;
import com.celfit.crawler.crawling.application.port.in.TriggerJobUseCase.TriggerResult;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/jobs")
public class JobController {

    private final TriggerJobUseCase jobService;

    public JobController(TriggerJobUseCase jobService) {
        this.jobService = jobService;
    }

    /** discover는 항상 전체 활성 검색어를 순차 실행. */
    @PostMapping("/discover")
    public ResponseEntity<Map<String, String>> discover() {
        return respond(JobName.DISCOVER, jobService.trigger(JobName.DISCOVER, TriggerType.MANUAL));
    }

    /** requalify=true면 EXCLUDED도 재판정 대상에 포함. */
    @PostMapping("/qualify")
    public ResponseEntity<Map<String, String>> qualify(
            @RequestParam(defaultValue = "false") boolean requalify) {
        return respond(JobName.QUALIFY, jobService.trigger(JobName.QUALIFY, TriggerType.MANUAL, requalify));
    }

    @PostMapping("/collect")
    public ResponseEntity<Map<String, String>> collect() {
        return respond(JobName.COLLECT, jobService.trigger(JobName.COLLECT, TriggerType.MANUAL));
    }

    private ResponseEntity<Map<String, String>> respond(JobName name, TriggerResult result) {
        return switch (result) {
            case ACCEPTED -> ResponseEntity.accepted()
                    .body(Map.of("job", name.name(), "result", "accepted"));
            case BUSY -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("job", name.name(), "result", "busy"));
        };
    }
}
