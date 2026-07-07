package com.celfit.crawler.ui;

import com.celfit.crawler.admin.StatusService;
import com.celfit.crawler.domain.CategoryRepository;
import com.celfit.crawler.domain.CrawlRunRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class UiController {

    private final StatusService statusService;
    private final CrawlRunRepository runs;
    private final CategoryRepository categories;

    public UiController(StatusService statusService, CrawlRunRepository runs,
                        CategoryRepository categories) {
        this.statusService = statusService;
        this.runs = runs;
        this.categories = categories;
    }

    @GetMapping("/")
    public String root() {
        return "redirect:/ui";
    }

    @GetMapping("/ui")
    public String dashboard(Model model) {
        model.addAttribute("summary", statusService.summary());
        return "dashboard";
    }

    @GetMapping("/ui/fragments/runs")
    public String runsFragment(Model model) {
        model.addAttribute("runs", runs.findTop50ByOrderByIdDesc());
        return "fragments/runs :: table";
    }

    @GetMapping("/ui/jobs")
    public String jobs(Model model) {
        model.addAttribute("categories", categories.findByEnabledTrue());
        return "jobs";
    }
}
