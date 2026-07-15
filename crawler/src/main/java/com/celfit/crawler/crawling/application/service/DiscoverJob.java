package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.application.port.out.SearchKeywordRepository;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentOrigin;
import com.celfit.crawler.content.domain.SearchKeyword;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.InfluencerDiscoveryRepository;
import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.application.port.out.RawDiscoveryPostRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerDiscovery;
import com.celfit.crawler.crawling.domain.RawDiscoveryPost;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발굴 잡 — 활성 search_keyword를 평탄하게 순회해 발굴 게시물의 작성자를 인플루언서로
 * upsert하고, 게시물은 content로 upsert, raw 원형은 항상 저장한다(발굴 출처 기록).
 * 발굴 경로 payload 원형화는 이번 스코프 아님 — 기존 계약 형태 그대로 저장한다.
 */
@Service
public class DiscoverJob {

    public record Summary(int newInfluencers, int knownInfluencers, int skippedItems, int failedKeywords) {}

    private final SearchKeywordRepository keywords;
    private final InfluencerRepository influencers;
    private final InfluencerDiscoveryRepository discoveries;
    private final ContentRepository contents;
    private final RawDiscoveryPostRepository rawDiscovery;
    private final DiscoverSourceSelector discoverSourceSelector;
    private final Clock clock;

    public DiscoverJob(SearchKeywordRepository keywords, InfluencerRepository influencers,
                       InfluencerDiscoveryRepository discoveries, ContentRepository contents,
                       RawDiscoveryPostRepository rawDiscovery, DiscoverSourceSelector discoverSourceSelector,
                       Clock clock) {
        this.keywords = keywords;
        this.influencers = influencers;
        this.discoveries = discoveries;
        this.contents = contents;
        this.rawDiscovery = rawDiscovery;
        this.discoverSourceSelector = discoverSourceSelector;
        this.clock = clock;
    }

    @Transactional
    public Summary run(TriggerType trigger) {
        int newInf = 0, known = 0, skipped = 0, failedKeywords = 0;
        for (SearchKeyword kw : keywords.findByEnabledTrue()) {
            CrawlExecutor.Execution ex;
            try {
                ex = discoverSourceSelector.fetch(kw.getKeyword(), trigger);
            } catch (ApifyException e) {
                failedKeywords++;
                continue;
            }
            for (Map<String, Object> item : ex.items()) {
                var parsed = DiscoveryItemParser.parse(item);
                if (parsed.isEmpty()) { skipped++; continue; }
                var d = parsed.get();
                var existing = influencers.findByUsername(d.ownerUsername());
                Influencer inf = existing.orElseGet(() -> influencers.save(new Influencer(d.ownerUsername())));
                if (existing.isPresent()) known++; else newInf++;
                discoveries.save(new InfluencerDiscovery(
                        inf.getId(), kw.getKeyword(), d.shortCode(), clock.instant()));
                Content content = contents.findByShortCode(d.shortCode()).orElseGet(() ->
                        contents.save(new Content(d.shortCode(), d.type(), d.ownerUsername(),
                                inf.getId(), d.uploadedAt(), clock.instant(), ContentOrigin.DISCOVERY)));
                // 중복 발굴이어도 raw는 항상 저장 — 원형 그대로 + 소스 태그
                RawDiscoveryPost raw = new RawDiscoveryPost(content.getId(), ex.runId(),
                        discoverSourceSelector.currentSource(), d.payload(), clock.instant());
                raw.setShortCode(d.shortCode());
                if (d.payload().get("caption") instanceof String caption) {
                    raw.setCaption(caption);
                }
                rawDiscovery.save(raw);
            }
        }
        return new Summary(newInf, known, skipped, failedKeywords);
    }
}
