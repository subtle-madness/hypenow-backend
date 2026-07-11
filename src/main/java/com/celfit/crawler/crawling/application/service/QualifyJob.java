package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.application.port.out.ActorInputs;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.domain.*;
import com.celfit.crawler.content.domain.*;
import com.celfit.crawler.settings.domain.*;
import com.celfit.crawler.crawling.application.port.out.*;
import com.celfit.crawler.content.application.port.out.*;
import com.celfit.crawler.settings.application.port.out.*;
import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QualifyJob {

    static final int PROFILE_CHUNK = 50;

    public record Summary(int profiled, int qualified, int excluded, int deferred) {}

    private final ContentRepository contents;
    private final AccountRepository accounts;
    private final CollectionRuleRepository rules;
    private final RawProfileRepository rawProfiles;
    private final Clock clock;
    private final ProfileSourceSelector profileSourceSelector;

    public QualifyJob(ContentRepository contents, AccountRepository accounts,
                      CollectionRuleRepository rules, RawProfileRepository rawProfiles,
                      Clock clock, ProfileSourceSelector profileSourceSelector) {
        this.contents = contents;
        this.accounts = accounts;
        this.rules = rules;
        this.rawProfiles = rawProfiles;
        this.clock = clock;
        this.profileSourceSelector = profileSourceSelector;
    }

    @Transactional
    public Summary run(TriggerType trigger) {
        return run(trigger, false);
    }

    /** requalify=true면 EXCLUDED도 재판정 (규칙 변경 후, raw_profile 재사용 — Apify 재호출 없음). */
    @Transactional
    public Summary run(TriggerType trigger, boolean requalify) {
        List<Content> targets = new ArrayList<>(contents.findByStatus(ContentStatus.PENDING));
        if (requalify) targets.addAll(contents.findByStatus(ContentStatus.EXCLUDED));

        int profiled = profileMissingAccounts(targets, trigger);

        int qualified = 0, excluded = 0, deferred = 0;
        Map<Long, Optional<CollectionRule>> ruleCache = new HashMap<>();
        for (Content c : targets) {
            CollectionRule rule = ruleCache
                    .computeIfAbsent(c.getCategoryId(), rules::findByCategoryId)
                    .orElse(null);
            if (rule == null || !rule.needsFollowers()) {
                c.setStatus(ContentStatus.QUALIFIED);
                c.setQualifiedAt(clock.instant());
                qualified++;
                continue;
            }
            Long followers = latestFollowers(c.getOwnerUsername());
            if (followers == null) {
                deferred++;  // 프로필 미확보 → PENDING 유지, 다음 실행 때 재시도
                continue;
            }
            boolean pass = rule.followersPass(followers);
            c.setStatus(pass ? ContentStatus.QUALIFIED : ContentStatus.EXCLUDED);
            c.setQualifiedAt(clock.instant());
            if (pass) qualified++; else excluded++;
        }
        return new Summary(profiled, qualified, excluded, deferred);
    }

    private int profileMissingAccounts(List<Content> targets, TriggerType trigger) {
        Set<String> usernames = targets.stream()
                .map(Content::getOwnerUsername)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (usernames.isEmpty()) return 0;
        List<Account> toProfile = accounts.findByUsernameInAndLastProfiledAtIsNull(usernames);

        int profiled = 0;
        for (List<Account> chunk : ActorInputs.chunk(toProfile, PROFILE_CHUNK)) {
            List<String> names = chunk.stream().map(Account::getUsername).toList();
            CrawlExecutor.Execution ex;
            try {
                ex = profileSourceSelector.fetchAndSupplement(names, trigger);
            } catch (ApifyException e) {
                continue;  // FAILED 기록됨 — 해당 청크 계정은 다음 실행 때 재시도
            }
            Map<String, Account> byName = chunk.stream()
                    .collect(Collectors.toMap(Account::getUsername, a -> a));
            for (Map<String, Object> item : ex.items()) {
                Account acct = item.get("username") instanceof String s ? byName.get(s) : null;
                if (acct == null) continue;
                rawProfiles.save(new RawProfile(acct.getId(), ex.runId(), item, clock.instant()));
                acct.setLastProfiledAt(clock.instant());
                profiled++;
            }
        }
        return profiled;
    }

    private Long latestFollowers(String username) {
        return accounts.findByUsername(username)
                .flatMap(a -> rawProfiles.findTopByAccountIdOrderByCapturedAtDesc(a.getId()))
                .map(rp -> rp.getPayload().get("followersCount"))
                .filter(Number.class::isInstance)
                .map(n -> ((Number) n).longValue())
                .orElse(null);
    }
}
