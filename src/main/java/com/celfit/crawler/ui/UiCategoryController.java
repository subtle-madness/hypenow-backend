package com.celfit.crawler.ui;

import com.celfit.crawler.admin.CategoryService;
import com.celfit.crawler.admin.CategoryService.RuleView;
import com.celfit.crawler.domain.ContentTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/ui")
public class UiCategoryController {

    private final CategoryService service;

    public UiCategoryController(CategoryService service) {
        this.service = service;
    }

    @GetMapping("/categories")
    public String page(Model model) {
        model.addAttribute("categories", service.list());
        model.addAttribute("filters", ContentTypeFilter.values());
        return "categories";
    }

    @PostMapping("/categories")
    public String addCategory(@RequestParam String name, RedirectAttributes ra) {
        return handle(ra, () -> service.create(name.trim()));
    }

    @PostMapping("/categories/{id}/toggle")
    public String toggleCategory(@PathVariable Long id, @RequestParam boolean enabled,
                                 RedirectAttributes ra) {
        return handle(ra, () -> service.setCategoryEnabled(id, enabled));
    }

    @PostMapping("/categories/{id}/delete")
    public String deleteCategory(@PathVariable Long id, RedirectAttributes ra) {
        return handle(ra, () -> service.deleteCategory(id));
    }

    @PostMapping("/categories/{id}/keywords")
    public String addKeyword(@PathVariable Long id, @RequestParam String keyword,
                             RedirectAttributes ra) {
        return handle(ra, () -> service.addKeyword(id, keyword.trim()));
    }

    @PostMapping("/keywords/{id}/toggle")
    public String toggleKeyword(@PathVariable Long id, @RequestParam boolean enabled,
                                RedirectAttributes ra) {
        return handle(ra, () -> service.setKeywordEnabled(id, enabled));
    }

    @PostMapping("/categories/{id}/rule")
    public String saveRule(@PathVariable Long id,
                           @RequestParam(required = false) Integer minFollowers,
                           @RequestParam(required = false) Integer maxFollowers,
                           @RequestParam ContentTypeFilter contentTypes,
                           RedirectAttributes ra) {
        return handle(ra, () -> service.upsertRule(id, new RuleView(minFollowers, maxFollowers, contentTypes)));
    }

    private String handle(RedirectAttributes ra, Runnable action) {
        try {
            action.run();
        } catch (ResponseStatusException e) {
            ra.addFlashAttribute("message", e.getReason());
        }
        return "redirect:/ui/categories";
    }
}
