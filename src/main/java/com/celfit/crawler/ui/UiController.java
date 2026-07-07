package com.celfit.crawler.ui;

import com.celfit.crawler.admin.StatusService;
import com.celfit.crawler.domain.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Controller
public class UiController {

    private final StatusService statusService;
    private final CrawlRunRepository runs;
    private final CategoryRepository categories;
    private final ContentRepository contents;
    private final AccountRepository accounts;
    private final RawPostDetailRepository rawDetails;
    private final RawCommentRepository rawComments;
    private final RawProfileRepository rawProfiles;
    private final ObjectMapper objectMapper;

    public UiController(StatusService statusService, CrawlRunRepository runs,
                        CategoryRepository categories, ContentRepository contents,
                        AccountRepository accounts, RawPostDetailRepository rawDetails,
                        RawCommentRepository rawComments, RawProfileRepository rawProfiles,
                        ObjectMapper objectMapper) {
        this.statusService = statusService;
        this.runs = runs;
        this.categories = categories;
        this.contents = contents;
        this.accounts = accounts;
        this.rawDetails = rawDetails;
        this.rawComments = rawComments;
        this.rawProfiles = rawProfiles;
        this.objectMapper = objectMapper;
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

    @GetMapping("/ui/contents")
    public String contents(@RequestParam(required = false) ContentStatus status,
                           @RequestParam(defaultValue = "0") int page, Model model) {
        page = Math.max(page, 0);
        var pageable = PageRequest.of(page, 50, Sort.by(Sort.Direction.DESC, "id"));
        var result = status == null ? contents.findAll(pageable)
                                    : contents.findByStatus(status, pageable);
        model.addAttribute("page", result);
        model.addAttribute("status", status);
        model.addAttribute("statuses", ContentStatus.values());
        return "contents";
    }

    @GetMapping("/ui/contents/{id}")
    public String contentDetail(@PathVariable Long id, Model model) {
        Content content = contents.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "콘텐츠 없음"));
        model.addAttribute("content", content);
        model.addAttribute("detailJson", rawDetails.findTopByContentIdOrderByCapturedAtDesc(id)
                .map(d -> pretty(d.getPayload())).orElse(null));
        model.addAttribute("comments", rawComments.findTop100ByContentIdOrderByIdDesc(id));
        model.addAttribute("profileJson", accounts.findByUsername(content.getOwnerUsername())
                .flatMap(a -> rawProfiles.findTopByAccountIdOrderByCapturedAtDesc(a.getId()))
                .map(p -> pretty(p.getPayload())).orElse(null));
        return "content-detail";
    }

    private String pretty(Object payload) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            return String.valueOf(payload);
        }
    }
}
