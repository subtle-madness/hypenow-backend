package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.crawling.application.port.in.TriggerJobUseCase;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/ui/jobs")
public class UiJobController {

    private final TriggerJobUseCase jobService;

    public UiJobController(TriggerJobUseCase jobService) {
        this.jobService = jobService;
    }

    @PostMapping("/{job}")
    public String trigger(@PathVariable String job,
                          @RequestParam(required = false) Long category,
                          @RequestParam(defaultValue = "false") boolean requalify,
                          RedirectAttributes ra) {
        try {
            JobName name = JobName.valueOf(job.toUpperCase(Locale.ROOT));
            String message = switch (jobService.trigger(name, category, TriggerType.MANUAL, requalify)) {
                case ACCEPTED -> name + " 실행 시작";
                case BUSY -> name + "이(가) 이미 실행 중입니다";
            };
            ra.addFlashAttribute("message", message);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("message", "실행 불가: " + e.getMessage());
        }
        return "redirect:/ui/jobs";
    }
}
