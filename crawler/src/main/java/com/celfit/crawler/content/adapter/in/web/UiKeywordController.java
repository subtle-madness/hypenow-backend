package com.celfit.crawler.content.adapter.in.web;

import com.celfit.crawler.content.application.port.out.SearchKeywordRepository;
import com.celfit.crawler.content.domain.SearchKeyword;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** /ui/keywords 화면 — jobs.html식 폼 POST → 리다이렉트 패턴. */
@Controller
@RequestMapping("/ui/keywords")
public class UiKeywordController {

    private final SearchKeywordRepository keywords;

    public UiKeywordController(SearchKeywordRepository keywords) {
        this.keywords = keywords;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("keywords", keywords.findAllByOrderByKeywordAsc());
        return "keywords";
    }

    @PostMapping
    public String add(@RequestParam String keyword, RedirectAttributes ra) {
        return handle(ra, () -> {
            String value = keyword.trim();
            if (value.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "키워드를 입력하세요");
            }
            if (keywords.findByKeyword(value).isPresent()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미 등록된 키워드");
            }
            keywords.save(new SearchKeyword(value, Instant.now()));
        });
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id, @RequestParam boolean enabled, RedirectAttributes ra) {
        return handle(ra, () -> {
            SearchKeyword k = keywords.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "키워드 없음"));
            k.setEnabled(enabled);
            keywords.save(k);
        });
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        return handle(ra, () -> keywords.deleteById(id));
    }

    private String handle(RedirectAttributes ra, Runnable action) {
        try {
            action.run();
        } catch (ResponseStatusException e) {
            ra.addFlashAttribute("message", e.getReason());
        }
        return "redirect:/ui/keywords";
    }
}
