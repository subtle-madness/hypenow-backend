package com.celfit.crawler.admin;

import com.celfit.crawler.admin.CategoryService.CategoryView;
import com.celfit.crawler.admin.CategoryService.KeywordView;
import com.celfit.crawler.admin.CategoryService.RuleView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin")
public class CategoryAdminController {

    public record CategoryReq(@NotBlank String name) {}
    public record KeywordReq(@NotBlank String keyword, String subcategory, String mainGroup) {}
    public record EnabledReq(boolean enabled) {}

    private final CategoryService service;

    public CategoryAdminController(CategoryService service) {
        this.service = service;
    }

    @GetMapping("/categories")
    public List<CategoryView> list() {
        return service.list();
    }

    @PostMapping("/categories")
    public ResponseEntity<CategoryView> create(@Valid @RequestBody CategoryReq req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req.name()));
    }

    @PatchMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setEnabled(@PathVariable Long id, @RequestBody EnabledReq req) {
        service.setCategoryEnabled(id, req.enabled());
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.deleteCategory(id);
    }

    @PostMapping("/categories/{id}/keywords")
    public ResponseEntity<KeywordView> addKeyword(@PathVariable Long id,
                                                  @Valid @RequestBody KeywordReq req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.addKeyword(id, req.keyword(), req.subcategory(), req.mainGroup()));
    }

    @PatchMapping("/keywords/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setKeywordEnabled(@PathVariable Long id, @RequestBody EnabledReq req) {
        service.setKeywordEnabled(id, req.enabled());
    }

    @DeleteMapping("/keywords/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteKeyword(@PathVariable Long id) {
        service.deleteKeyword(id);
    }

    /** subcategory 생략 시 대분류 전체 삭제. */
    @DeleteMapping("/categories/{id}/groups")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGroup(@PathVariable Long id, @RequestParam String mainGroup,
                            @RequestParam(required = false) String subcategory) {
        service.deleteGroup(id, mainGroup, subcategory);
    }

    @PutMapping("/categories/{id}/rule")
    public RuleView upsertRule(@PathVariable Long id, @RequestBody RuleView req) {
        return service.upsertRule(id, req);
    }
}
