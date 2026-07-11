package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.celfit.crawler.content.application.port.out.CollectionRuleRepository;
import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentStatus;
import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.application.port.out.AccountRepository;
import com.celfit.crawler.crawling.application.port.out.RawProfileRepository;
import com.celfit.crawler.crawling.domain.Account;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * QualifyJob이 프로필 수집을 (구) CrawlExecutor.execute(...Actors.PROFILE...) 직접 호출이 아니라
 * ProfileSourceSelector.fetchAndSupplement(...)에 위임하는지 검증하는 순수 mockito 배선 테스트.
 * executor 필드는 QualifyJob에서 완전히 제거되어 있어 이 위임 외에는 프로필을 가져올 경로가 없다.
 */
class QualifyJobProfileSourceRoutingTest {

    @Test
    void profileMissingAccounts는_ProfileSourceSelector_경유로_프로필을_수집한다() {
        ContentRepository contents = mock(ContentRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        CollectionRuleRepository rules = mock(CollectionRuleRepository.class);
        RawProfileRepository rawProfiles = mock(RawProfileRepository.class);
        ProfileSourceSelector selector = mock(ProfileSourceSelector.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-11T00:00:00Z"), ZoneOffset.UTC);

        Content c = new Content("sc1", ContentType.REELS, "kim",
                Instant.parse("2026-07-01T00:00:00Z"), 1L, "메이크업", Instant.now());
        Account kim = new Account("kim");

        when(contents.findByStatus(any(), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(c)));
        when(accounts.findByUsernameInAndLastProfiledAtIsNull(Set.of("kim"))).thenReturn(List.of(kim));
        when(rules.findByCategoryId(1L)).thenReturn(Optional.empty());

        Map<String, Object> item = Map.of("username", "kim", "followersCount", 500L);
        when(selector.fetchAndSupplement(List.of("kim"), TriggerType.MANUAL))
                .thenReturn(new CrawlExecutor.Execution(9L, List.of(item)));

        com.celfit.crawler.settings.application.service.SettingsService settings =
                mock(com.celfit.crawler.settings.application.service.SettingsService.class);
        when(settings.qualifyBatchLimit()).thenReturn(500);

        QualifyJob job = new QualifyJob(contents, accounts, rules, rawProfiles, clock, selector, settings);
        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.profiled()).isEqualTo(1);
        verify(selector).fetchAndSupplement(List.of("kim"), TriggerType.MANUAL);
        verifyNoMoreInteractions(selector);
        verify(rawProfiles).save(any());
    }
}
