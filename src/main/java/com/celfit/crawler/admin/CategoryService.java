package com.celfit.crawler.admin;

import com.celfit.crawler.domain.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CategoryService {

    public record KeywordView(Long id, String keyword, boolean enabled) {}
    public record RuleView(Integer minFollowers, Integer maxFollowers, ContentTypeFilter contentTypes) {}
    public record CategoryView(Long id, String name, boolean enabled,
                               List<KeywordView> keywords, RuleView rule) {}

    private final CategoryRepository categories;
    private final CategoryKeywordRepository keywords;
    private final CollectionRuleRepository rules;

    public CategoryService(CategoryRepository categories, CategoryKeywordRepository keywords,
                           CollectionRuleRepository rules) {
        this.categories = categories;
        this.keywords = keywords;
        this.rules = rules;
    }

    @Transactional(readOnly = true)
    public List<CategoryView> list() {
        return categories.findAll().stream().map(this::toView).toList();
    }

    @Transactional
    public CategoryView create(String name) {
        if (categories.findByName(name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 존재하는 카테고리: " + name);
        }
        return toView(categories.save(new Category(name)));
    }

    @Transactional
    public void setCategoryEnabled(Long id, boolean enabled) {
        Category c = categories.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "카테고리 없음: " + id));
        c.setEnabled(enabled);
    }

    @Transactional
    public KeywordView addKeyword(Long categoryId, String keyword) {
        if (categories.findById(categoryId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "카테고리 없음: " + categoryId);
        }
        if (keywords.existsByCategoryIdAndKeyword(categoryId, keyword)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 존재하는 키워드: " + keyword);
        }
        CategoryKeyword saved = keywords.save(new CategoryKeyword(categoryId, keyword));
        return new KeywordView(saved.getId(), saved.getKeyword(), saved.isEnabled());
    }

    @Transactional
    public void setKeywordEnabled(Long keywordId, boolean enabled) {
        CategoryKeyword kw = keywords.findById(keywordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "키워드 없음: " + keywordId));
        kw.setEnabled(enabled);
    }

    @Transactional
    public RuleView upsertRule(Long categoryId, RuleView req) {
        if (categories.findById(categoryId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "카테고리 없음: " + categoryId);
        }
        CollectionRule rule = rules.findByCategoryId(categoryId)
                .orElseGet(() -> new CollectionRule(categoryId));
        rule.setMinFollowers(req.minFollowers());
        rule.setMaxFollowers(req.maxFollowers());
        rule.setContentTypes(req.contentTypes() == null ? ContentTypeFilter.ALL : req.contentTypes());
        rule = rules.save(rule);
        return new RuleView(rule.getMinFollowers(), rule.getMaxFollowers(), rule.getContentTypes());
    }

    private CategoryView toView(Category c) {
        List<KeywordView> kws = keywords.findByCategoryId(c.getId()).stream()
                .map(k -> new KeywordView(k.getId(), k.getKeyword(), k.isEnabled()))
                .toList();
        RuleView rule = rules.findByCategoryId(c.getId())
                .map(r -> new RuleView(r.getMinFollowers(), r.getMaxFollowers(), r.getContentTypes()))
                .orElse(null);
        return new CategoryView(c.getId(), c.getName(), c.isEnabled(), kws, rule);
    }
}
