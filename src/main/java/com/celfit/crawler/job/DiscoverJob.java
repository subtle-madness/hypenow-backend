package com.celfit.crawler.job;

import com.celfit.crawler.admin.SettingsService;
import com.celfit.crawler.apify.Actors;
import com.celfit.crawler.apify.ActorInputs;
import com.celfit.crawler.apify.ApifyException;
import com.celfit.crawler.domain.*;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiscoverJob {

    public record Summary(int newContents, int duplicates, int skipped, int failedKeywords) {}

    private final CategoryRepository categories;
    private final CategoryKeywordRepository keywords;
    private final CollectionRuleRepository rules;
    private final AccountRepository accounts;
    private final ContentRepository contents;
    private final RawDiscoveryPostRepository rawDiscovery;
    private final CrawlExecutor executor;
    private final SettingsService settings;
    private final Clock clock;

    public DiscoverJob(CategoryRepository categories, CategoryKeywordRepository keywords,
                       CollectionRuleRepository rules, AccountRepository accounts,
                       ContentRepository contents, RawDiscoveryPostRepository rawDiscovery,
                       CrawlExecutor executor, SettingsService settings, Clock clock) {
        this.categories = categories;
        this.keywords = keywords;
        this.rules = rules;
        this.accounts = accounts;
        this.contents = contents;
        this.rawDiscovery = rawDiscovery;
        this.executor = executor;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public Summary run(long categoryId, TriggerType trigger) {
        categories.findById(categoryId)
                .filter(Category::isEnabled)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않거나 비활성 카테고리: " + categoryId));
        ContentTypeFilter filter = rules.findByCategoryId(categoryId)
                .map(CollectionRule::getContentTypes)
                .orElse(ContentTypeFilter.ALL);

        int newContents = 0, duplicates = 0, skipped = 0, failedKeywords = 0;
        for (CategoryKeyword kw : keywords.findByCategoryIdAndEnabledTrue(categoryId)) {
            CrawlExecutor.Execution ex;
            try {
                ex = executor.execute(JobName.DISCOVER, trigger, categoryId, kw.getKeyword(),
                        Actors.DISCOVERY, ActorInputs.discovery(kw.getKeyword(), settings.resultsLimit()));
            } catch (ApifyException e) {
                failedKeywords++;  // crawl_run에 FAILED 기록됨 — 다음 키워드 계속
                continue;
            }
            for (Map<String, Object> item : ex.items()) {
                var parsed = DiscoveryItemParser.parse(item);
                if (parsed.isEmpty() || !filter.allows(parsed.get().type())) {
                    skipped++;  // content_types 불일치·필수 필드 결손 → 등록·raw 모두 skip
                    continue;
                }
                var d = parsed.get();
                accounts.findByUsername(d.ownerUsername())
                        .orElseGet(() -> accounts.save(new Account(d.ownerUsername())));
                var existing = contents.findByShortCode(d.shortCode());
                Content content = existing.orElseGet(() -> contents.save(new Content(
                        d.shortCode(), d.type(), d.ownerUsername(), d.uploadedAt(),
                        categoryId, kw.getKeyword(), kw.getSubcategory(), kw.getMainGroup(),
                        clock.instant())));
                if (existing.isPresent()) duplicates++; else newContents++;
                // 중복 발굴이어도 raw는 항상 저장 — "언제 어떤 키워드에서 발견됐나" 이력
                rawDiscovery.save(new RawDiscoveryPost(content.getId(), ex.runId(), d.payload(), clock.instant()));
            }
        }
        return new Summary(newContents, duplicates, skipped, failedKeywords);
    }
}
