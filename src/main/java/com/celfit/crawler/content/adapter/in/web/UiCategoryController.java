package com.celfit.crawler.content.adapter.in.web;

import com.celfit.crawler.content.application.service.CategoryService;
import com.celfit.crawler.content.application.service.CategoryService.CategoryView;
import com.celfit.crawler.content.application.service.CategoryService.KeywordView;
import com.celfit.crawler.content.application.service.CategoryService.RuleView;
import com.celfit.crawler.content.domain.ContentTypeFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * 카테고리(뷰티/패션)는 탭 — cat 파라미터로 선택, 없으면 첫 번째.
     * 키워드는 대분류 > 중분류 2단 트리로 그룹핑해 내려준다 (소분류 = keyword).
     */
    @GetMapping("/categories")
    public String page(@RequestParam(required = false) Long cat, Model model) {
        List<CategoryView> all = service.list();
        CategoryView selected = all.stream()
                .filter(c -> cat == null || c.id().equals(cat))
                .findFirst().orElse(null);

        Map<String, Map<String, List<KeywordView>>> tree = new LinkedHashMap<>();
        // 그룹별 파생 상태: 켜진 소분류가 하나라도 있으면 true(=활성 → "제외" 버튼 노출).
        Map<String, Boolean> mainGroupEnabled = new LinkedHashMap<>();
        Map<String, Map<String, Boolean>> subGroupEnabled = new LinkedHashMap<>();
        if (selected != null) {
            for (KeywordView k : selected.keywords()) {
                tree.computeIfAbsent(k.mainGroup(), g -> new LinkedHashMap<>())
                        .computeIfAbsent(k.subcategory(), s -> new ArrayList<>()).add(k);
                mainGroupEnabled.merge(k.mainGroup(), k.enabled(), Boolean::logicalOr);
                subGroupEnabled.computeIfAbsent(k.mainGroup(), g -> new LinkedHashMap<>())
                        .merge(k.subcategory(), k.enabled(), Boolean::logicalOr);
            }
        }
        model.addAttribute("categories", all);
        model.addAttribute("selected", selected);
        model.addAttribute("tree", tree);
        model.addAttribute("mainGroupEnabled", mainGroupEnabled);
        model.addAttribute("subGroupEnabled", subGroupEnabled);
        model.addAttribute("filters", ContentTypeFilter.values());
        return "categories";
    }

    @PostMapping("/categories")
    public String addCategory(@RequestParam String name, RedirectAttributes ra) {
        return handle(ra, null, () -> service.create(name.trim()));
    }

    @PostMapping("/categories/{id}/toggle")
    public String toggleCategory(@PathVariable Long id, @RequestParam boolean enabled,
                                 RedirectAttributes ra) {
        return handle(ra, id, () -> service.setCategoryEnabled(id, enabled));
    }

    @PostMapping("/categories/{id}/delete")
    public String deleteCategory(@PathVariable Long id, RedirectAttributes ra) {
        return handle(ra, null, () -> service.deleteCategory(id));
    }

    @PostMapping("/categories/{id}/keywords")
    public String addKeyword(@PathVariable Long id, @RequestParam String keyword,
                             @RequestParam(required = false) String subcategory,
                             @RequestParam(required = false) String mainGroup,
                             @RequestParam(required = false) Long cat,
                             RedirectAttributes ra) {
        return handle(ra, cat, () -> service.addKeyword(id, keyword.trim(), subcategory, mainGroup));
    }

    @PostMapping("/keywords/{id}/toggle")
    public String toggleKeyword(@PathVariable Long id, @RequestParam boolean enabled,
                                @RequestParam(required = false) Long cat,
                                RedirectAttributes ra) {
        return handle(ra, cat, () -> service.setKeywordEnabled(id, enabled));
    }

    @PostMapping("/keywords/{id}/delete")
    public String deleteKeyword(@PathVariable Long id, @RequestParam(required = false) Long cat,
                                RedirectAttributes ra) {
        return handle(ra, cat, () -> service.deleteKeyword(id));
    }

    @PostMapping("/categories/{id}/groups/delete")
    public String deleteGroup(@PathVariable Long id, @RequestParam String mainGroup,
                              @RequestParam(required = false) String subcategory,
                              @RequestParam(required = false) Long cat,
                              RedirectAttributes ra) {
        return handle(ra, cat, () -> service.deleteGroup(id, mainGroup, subcategory));
    }

    @PostMapping("/categories/{id}/groups/toggle")
    public String toggleGroup(@PathVariable Long id, @RequestParam String mainGroup,
                              @RequestParam(required = false) String subcategory,
                              @RequestParam boolean enabled,
                              @RequestParam(required = false) Long cat,
                              RedirectAttributes ra) {
        return handle(ra, cat, () -> service.setGroupEnabled(id, mainGroup, subcategory, enabled));
    }

    @PostMapping("/categories/{id}/rule")
    public String saveRule(@PathVariable Long id,
                           @RequestParam(required = false) Integer minFollowers,
                           @RequestParam(required = false) Integer maxFollowers,
                           @RequestParam ContentTypeFilter contentTypes,
                           @RequestParam(required = false) Long cat,
                           RedirectAttributes ra) {
        return handle(ra, cat, () -> service.upsertRule(id, new RuleView(minFollowers, maxFollowers, contentTypes)));
    }

    /** 액션 후 선택 중이던 대분류 탭 유지. */
    private String handle(RedirectAttributes ra, Long cat, Runnable action) {
        try {
            action.run();
        } catch (ResponseStatusException e) {
            ra.addFlashAttribute("message", e.getReason());
        }
        return cat == null ? "redirect:/ui/categories" : "redirect:/ui/categories?cat=" + cat;
    }
}
