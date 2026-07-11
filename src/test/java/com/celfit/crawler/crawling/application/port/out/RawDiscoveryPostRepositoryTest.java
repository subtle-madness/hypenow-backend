package com.celfit.crawler.crawling.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.content.application.port.out.CategoryRepository;
import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.Category;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentType;
import com.celfit.crawler.crawling.domain.CrawlRun;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawDiscoveryPost;
import com.celfit.crawler.crawling.domain.TriggerType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RawDiscoveryPostRepositoryTest extends IntegrationTest {

    @Autowired RawDiscoveryPostRepository rawDiscovery;
    @Autowired ContentRepository contents;
    @Autowired CategoryRepository categories;
    @Autowired CrawlRunRepository runs;

    static final Instant T = Instant.parse("2026-07-11T00:00:00Z");

    Long catId;

    Long content(String shortCode) {
        if (catId == null) catId = categories.save(new Category("메이크업")).getId();
        return contents.save(new Content(shortCode, ContentType.REELS, "u_" + shortCode, T, catId, "메이크업", T)).getId();
    }

    Long run() {
        return runs.save(new CrawlRun(JobName.DISCOVER, TriggerType.MANUAL, null, "kw", "actor", T)).getId();
    }

    void discovered(Long contentId, Long runId) {
        rawDiscovery.save(new RawDiscoveryPost(contentId, runId, Map.of("shortCode", "x"), T));
    }

    @Test
    void 중복_재발굴은_더_이른_발굴이_있는_건만_센다() {
        Long c1 = content("c1");
        Long c2 = content("c2");
        Long r98 = run();
        Long r101 = run();
        Long r102 = run();

        discovered(c1, r98);    // c1 최초 발굴
        discovered(c1, r101);   // c1 재발굴(중복)
        discovered(c1, r102);   // c1 재발굴(중복)
        discovered(c2, r102);   // c2 최초 발굴(신규)

        var stats = rawDiscovery.discoveryStats(List.of(r98, r101, r102)).stream()
                .collect(java.util.stream.Collectors.toMap(
                        RawDiscoveryPostRepository.RunDiscoveryStat::getRunId, s -> s));

        assertThat(stats.get(r98).getTotal()).isEqualTo(1);
        assertThat(stats.get(r98).getDuplicates()).isEqualTo(0);   // 최초 → 신규

        assertThat(stats.get(r101).getTotal()).isEqualTo(1);
        assertThat(stats.get(r101).getDuplicates()).isEqualTo(1);  // 전부 중복

        assertThat(stats.get(r102).getTotal()).isEqualTo(2);
        assertThat(stats.get(r102).getDuplicates()).isEqualTo(1);  // c1 중복, c2 신규
    }
}
