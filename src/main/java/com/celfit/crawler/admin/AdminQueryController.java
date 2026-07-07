package com.celfit.crawler.admin;

import com.celfit.crawler.domain.CrawlRun;
import com.celfit.crawler.domain.CrawlRunRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminQueryController {

    public record RunView(Long id, String job, String trigger, Long categoryId, String keyword,
                          String actorId, String apifyRunId, String status, Integer itemCount,
                          String errorMessage, Instant startedAt, Instant finishedAt) {
        static RunView from(CrawlRun r) {
            return new RunView(r.getId(), r.getJob().name(), r.getTriggerType().name(),
                    r.getCategoryId(), r.getKeyword(), r.getActorId(), r.getApifyRunId(),
                    r.getStatus().name(), r.getItemCount(), r.getErrorMessage(),
                    r.getStartedAt(), r.getFinishedAt());
        }
    }

    private final CrawlRunRepository runs;
    private final StatusService statusService;

    public AdminQueryController(CrawlRunRepository runs, StatusService statusService) {
        this.runs = runs;
        this.statusService = statusService;
    }

    @GetMapping("/runs")
    public List<RunView> runs() {
        return runs.findTop50ByOrderByIdDesc().stream().map(RunView::from).toList();
    }

    @GetMapping("/status")
    public StatusService.StatusSummary status() {
        return statusService.summary();
    }
}
