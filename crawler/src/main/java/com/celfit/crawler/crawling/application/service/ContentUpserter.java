package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentOrigin;
import com.celfit.crawler.crawling.domain.Influencer;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 열거 산출물 content upsert — COLLECT(피드)·REELS(클립)가 공유. 신규는 ENUMERATION(수집 대상)으로
 * 생성하고, 기존 행이 발굴 부산물(DISCOVERY)이었다면 정식 수집 범위에 들어온 것이므로 승격한다.
 * DB 왕복은 일괄 조회 1회 + 일괄 저장 1회 — 방문당 게시물 12개를 개별 조회·저장(~24왕복)하던
 * 것이 방문 후처리 지연의 주범이었다.
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
        if (items.isEmpty()) return 0;
        Map<String, Content> existing = contents.findByShortCodeIn(
                        items.stream().map(MediaItemExtractor.MediaItem::shortCode).toList()).stream()
                .collect(Collectors.toMap(Content::getShortCode, Function.identity()));
        // shortCode 키 맵 — 응답에 같은 게시물이 중복돼도(고정 게시물 등) 일괄 insert에서 한 번만 생성
        Map<String, Content> toInsert = new LinkedHashMap<>();
        for (var item : items) {
            Content c = existing.get(item.shortCode());
            if (c == null) {
                toInsert.computeIfAbsent(item.shortCode(), sc -> new Content(sc, item.type(),
                        inf.getUsername(), inf.getId(), item.takenAt(), clock.instant(),
                        ContentOrigin.ENUMERATION));
            } else if (c.getOrigin() == ContentOrigin.DISCOVERY) {
                c.setOrigin(ContentOrigin.ENUMERATION);   // 관리 엔티티 — 커밋 시 더티체킹으로 반영
            }
        }
        if (!toInsert.isEmpty()) contents.saveAll(java.util.List.copyOf(toInsert.values()));
        return items.size();
    }
}
