package com.celfit.crawler.content.application.service;

import com.celfit.crawler.crawling.domain.*;
import com.celfit.crawler.content.domain.*;
import com.celfit.crawler.settings.domain.*;
import com.celfit.crawler.crawling.application.port.out.*;
import com.celfit.crawler.content.application.port.out.*;
import com.celfit.crawler.settings.application.port.out.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CategoryService {

    public record KeywordView(Long id, String keyword, String subcategory, String mainGroup,
                              boolean enabled) {}
    public record RuleView(Integer minFollowers, Integer maxFollowers, ContentTypeFilter contentTypes) {}
    public record CategoryView(Long id, String name, boolean enabled,
                               List<KeywordView> keywords, RuleView rule) {}

    private final CategoryRepository categories;
    private final CategoryKeywordRepository keywords;
    private final CollectionRuleRepository rules;
    private final CrawlRunRepository crawlRuns;
    private final ContentRepository contents;

    public CategoryService(CategoryRepository categories, CategoryKeywordRepository keywords,
                           CollectionRuleRepository rules, CrawlRunRepository crawlRuns,
                           ContentRepository contents) {
        this.categories = categories;
        this.keywords = keywords;
        this.rules = rules;
        this.crawlRuns = crawlRuns;
        this.contents = contents;
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

    /** 수집 이력(crawl_run·content)이 있으면 거부 — 이력 추적성 보존. 키워드·규칙은 함께 삭제. */
    @Transactional
    public void deleteCategory(Long id) {
        Category c = categories.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "카테고리 없음: " + id));
        if (crawlRuns.existsByCategoryId(id) || contents.existsByCategoryId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "수집 이력이 있는 카테고리는 삭제할 수 없음 (비활성화 사용): " + c.getName());
        }
        keywords.deleteByCategoryId(id);
        rules.deleteByCategoryId(id);
        categories.delete(c);
    }

    /**
     * keyword는 소분류(해시태그 검색어). 중분류가 비면 키워드를, 대분류가 비면 중분류를 승계.
     * 계층: 카테고리 > 대분류 > 중분류 > 소분류.
     */
    @Transactional
    public KeywordView addKeyword(Long categoryId, String keyword, String subcategory, String mainGroup) {
        if (categories.findById(categoryId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "카테고리 없음: " + categoryId);
        }
        if (keywords.existsByCategoryIdAndKeyword(categoryId, keyword)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 존재하는 키워드: " + keyword);
        }
        String sub = subcategory == null || subcategory.isBlank() ? keyword : subcategory.trim();
        String main = mainGroup == null || mainGroup.isBlank() ? sub : mainGroup.trim();
        CategoryKeyword saved = keywords.save(new CategoryKeyword(categoryId, keyword, sub, main));
        return toView(saved);
    }

    /** 소분류(키워드 행) 1개 삭제. 수집된 콘텐츠의 분류 라벨은 영향 없음. */
    @Transactional
    public void deleteKeyword(Long keywordId) {
        CategoryKeyword kw = keywords.findById(keywordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "키워드 없음: " + keywordId));
        keywords.delete(kw);
    }

    /** 대분류(subcategory=null) 또는 중분류 단위 일괄 삭제. */
    @Transactional
    public void deleteGroup(Long categoryId, String mainGroup, String subcategory) {
        if (categories.findById(categoryId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "카테고리 없음: " + categoryId);
        }
        if (subcategory == null || subcategory.isBlank()) {
            keywords.deleteByCategoryIdAndMainGroup(categoryId, mainGroup);
        } else {
            keywords.deleteByCategoryIdAndMainGroupAndSubcategory(categoryId, mainGroup, subcategory);
        }
    }

    @Transactional
    public void setKeywordEnabled(Long keywordId, boolean enabled) {
        CategoryKeyword kw = keywords.findById(keywordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "키워드 없음: " + keywordId));
        kw.setEnabled(enabled);
    }

    /**
     * 대분류(subcategory=null) 또는 중분류 단위로 소속 소분류(키워드)의 enabled를 일괄 설정.
     * discover는 enabled만 보므로 "그룹 제외 = 그 밑 키워드 제외"가 그대로 적용된다.
     */
    @Transactional
    public void setGroupEnabled(Long categoryId, String mainGroup, String subcategory, boolean enabled) {
        List<CategoryKeyword> group = (subcategory == null || subcategory.isBlank())
                ? keywords.findByCategoryIdAndMainGroup(categoryId, mainGroup)
                : keywords.findByCategoryIdAndMainGroupAndSubcategory(categoryId, mainGroup, subcategory);
        group.forEach(k -> k.setEnabled(enabled));
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

    private KeywordView toView(CategoryKeyword k) {
        return new KeywordView(k.getId(), k.getKeyword(), k.getSubcategory(), k.getMainGroup(),
                k.isEnabled());
    }

    private CategoryView toView(Category c) {
        List<KeywordView> kws = keywords.findByCategoryId(c.getId()).stream()
                .map(this::toView)
                .toList();
        RuleView rule = rules.findByCategoryId(c.getId())
                .map(r -> new RuleView(r.getMinFollowers(), r.getMaxFollowers(), r.getContentTypes()))
                .orElse(null);
        return new CategoryView(c.getId(), c.getName(), c.isEnabled(), kws, rule);
    }
}
