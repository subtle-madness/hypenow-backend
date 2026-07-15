package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentOrigin;
import com.celfit.crawler.crawling.domain.Influencer;
import java.time.Clock;
import java.util.Collection;
import org.springframework.stereotype.Service;

/**
 * 열거 산출물 content upsert — COLLECT(피드)·REELS(클립)가 공유. 신규는 ENUMERATION(수집 대상)으로
 * 생성하고, 기존 행이 발굴 부산물(DISCOVERY)이었다면 정식 수집 범위에 들어온 것이므로 승격한다.
 */
@Service
public class ContentUpserter {

    private final ContentRepository contents;
    private final Clock clock;

    public ContentUpserter(ContentRepository contents, Clock clock) {
        this.contents = contents;
        this.clock = clock;
    }

    /** upsert한(신규 생성 + 기존 확인) 아이템 수를 반환한다. 호출자 트랜잭션에 합류한다. */
    public int upsert(Collection<MediaItemExtractor.MediaItem> items, Influencer inf) {
        int upserted = 0;
        for (var item : items) {
            Content existing = contents.findByShortCode(item.shortCode()).orElse(null);
            if (existing == null) {
                contents.save(new Content(item.shortCode(), item.type(),
                        inf.getUsername(), inf.getId(), item.takenAt(), clock.instant(),
                        ContentOrigin.ENUMERATION));
            } else if (existing.getOrigin() == ContentOrigin.DISCOVERY) {
                existing.setOrigin(ContentOrigin.ENUMERATION);
            }
            upserted++;
        }
        return upserted;
    }
}
