package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.crawling.application.port.in.TriggerJobUseCase;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/jobs")
public class JobController {

    private final TriggerJobUseCase jobService;

    public JobController(TriggerJobUseCase jobService) {
        this.jobService = jobService;
    }

    /** discover는 category 생략 시 전체 활성 카테고리를 순차 실행. requalify는 qualify에서만 의미 있음. */
    @PostMapping("/{job}")
    public ResponseEntity<Map<String, String>> trigger(@PathVariable String job,
                                                       @RequestParam(required = false) Long category,
                                                       @RequestParam(defaultValue = "false") boolean requalify) {
        JobName name;
        try {
            name = JobName.valueOf(job.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "알 수 없는 잡: " + job));
        }
        return switch (jobService.trigger(name, category, TriggerType.MANUAL, requalify)) {
            case ACCEPTED -> ResponseEntity.accepted()
                    .body(Map.of("job", name.name(), "result", "accepted"));
            case BUSY -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("job", name.name(), "result", "busy"));
        };
    }
}
