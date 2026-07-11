package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.celfit.crawler.content.application.port.out.CategoryKeywordRepository;
import com.celfit.crawler.content.application.port.out.CategoryRepository;
import com.celfit.crawler.content.application.port.out.CollectionRuleRepository;
import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.Category;
import com.celfit.crawler.content.domain.CategoryKeyword;
import com.celfit.crawler.crawling.application.port.out.AccountRepository;
import com.celfit.crawler.crawling.application.port.out.RawDiscoveryPostRepository;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * DiscoverJob이 (구) executor.execute(...Actors.DISCOVERY...) 직접 호출이 아니라
 * DiscoverSourceSelector.fetch(...)에 위임하는지 검증하는 순수 mockito 배선 테스트.
 */
class DiscoverJobRoutingTest {

    @Test
    void run은_DiscoverSourceSelector_경유로_발굴한다() {
        CategoryRepository categories = mock(CategoryRepository.class);
        CategoryKeywordRepository keywords = mock(CategoryKeywordRepository.class);
        CollectionRuleRepository rules = mock(CollectionRuleRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        ContentRepository contents = mock(ContentRepository.class);
        RawDiscoveryPostRepository rawDiscovery = mock(RawDiscoveryPostRepository.class);
        DiscoverSourceSelector selector = mock(DiscoverSourceSelector.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-11T00:00:00Z"), ZoneOffset.UTC);

        Category cat = mock(Category.class);
        when(cat.isEnabled()).thenReturn(true);
        when(categories.findById(1L)).thenReturn(Optional.of(cat));
        CategoryKeyword kw = mock(CategoryKeyword.class);
        when(kw.getKeyword()).thenReturn("립");
        when(kw.getSubcategory()).thenReturn("");
        when(kw.getMainGroup()).thenReturn("");
        when(keywords.findByCategoryIdAndEnabledTrue(1L)).thenReturn(List.of(kw));
        when(rules.findByCategoryId(1L)).thenReturn(Optional.empty());
        when(accounts.findByUsername("owysim")).thenReturn(Optional.empty());
        when(accounts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contents.findByShortCode("DZr1AvEMT0M")).thenReturn(Optional.empty());
        when(contents.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> item = Map.of("shortCode", "DZr1AvEMT0M", "productType", "clips",
                "timestamp", "2026-06-17T11:11:05Z", "ownerUsername", "owysim");
        when(selector.fetch(1L, "립", TriggerType.MANUAL))
                .thenReturn(new CrawlExecutor.Execution(9L, List.of(item)));

        DiscoverJob job = new DiscoverJob(categories, keywords, rules, accounts,
                contents, rawDiscovery, selector, clock);
        var summary = job.run(1L, TriggerType.MANUAL);

        assertThat(summary.newContents()).isEqualTo(1);
        verify(selector).fetch(1L, "립", TriggerType.MANUAL);
        verifyNoMoreInteractions(selector);
        verify(rawDiscovery).save(any());
    }
}
