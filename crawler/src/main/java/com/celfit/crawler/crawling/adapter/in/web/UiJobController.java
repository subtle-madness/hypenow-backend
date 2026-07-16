package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.crawling.application.port.in.TriggerJobUseCase;
import com.celfit.crawler.crawling.application.port.in.TriggerJobUseCase.TriggerResult;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.TriggerType;
import org.springframework.stereotype.Controller;
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

    @PostMapping("/discover")
    public String discover(RedirectAttributes ra) {
        return respond(JobName.DISCOVER, jobService.trigger(JobName.DISCOVER, TriggerType.MANUAL), ra);
    }

    @PostMapping("/qualify")
    public String qualify(@RequestParam(defaultValue = "false") boolean requalify, RedirectAttributes ra) {
        return respond(JobName.QUALIFY,
                jobService.trigger(JobName.QUALIFY, TriggerType.MANUAL, requalify), ra);
    }

    @PostMapping("/collect")
    public String collect(RedirectAttributes ra) {
        return respond(JobName.COLLECT, jobService.trigger(JobName.COLLECT, TriggerType.MANUAL), ra);
    }

    @PostMapping("/beauty")
    public String beauty(@RequestParam(defaultValue = "false") boolean rejudge, RedirectAttributes ra) {
        return respond(JobName.BEAUTY, jobService.trigger(JobName.BEAUTY, TriggerType.MANUAL, rejudge), ra);
    }

    @PostMapping("/resnapshot")
    public String resnapshot(RedirectAttributes ra) {
        return respond(JobName.RESNAPSHOT, jobService.trigger(JobName.RESNAPSHOT, TriggerType.MANUAL), ra);
    }

    @PostMapping("/similar")
    public String similar(RedirectAttributes ra) {
        return respond(JobName.SIMILAR, jobService.trigger(JobName.SIMILAR, TriggerType.MANUAL), ra);
    }

    @PostMapping("/reels")
    public String reels(RedirectAttributes ra) {
        return respond(JobName.REELS, jobService.trigger(JobName.REELS, TriggerType.MANUAL), ra);
    }

    private String respond(JobName name, TriggerResult result, RedirectAttributes ra) {
        String message = switch (result) {
            case ACCEPTED -> name + " 실행 시작";
            case BUSY -> name + "이(가) 이미 실행 중입니다";
        };
        ra.addFlashAttribute("message", message);
        return "redirect:/ui/jobs";
    }
}
