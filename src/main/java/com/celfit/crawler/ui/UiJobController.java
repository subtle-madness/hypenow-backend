package com.celfit.crawler.ui;

import com.celfit.crawler.domain.JobName;
import com.celfit.crawler.domain.TriggerType;
import com.celfit.crawler.job.JobService;
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

    private final JobService jobService;

    public UiJobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping("/{job}")
    public String trigger(@PathVariable String job,
                          @RequestParam(required = false) Long category,
                          RedirectAttributes ra) {
        try {
            JobName name = JobName.valueOf(job.toUpperCase(Locale.ROOT));
            String message = switch (jobService.trigger(name, category, TriggerType.MANUAL)) {
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
