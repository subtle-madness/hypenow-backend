package com.celfit.crawler.content.application.service;

import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.crawling.application.service.MediaItemExtractor.MediaItem;
import com.celfit.crawler.crawling.domain.RawSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 캡션 원문 적재 — 라이브 수집(COLLECT·REELS)과 일회성 백필(CAPTION_BACKFILL)이 공유한다.
 * content 단위 최신 1건만 두고, 충돌 시 captured_at이 더 최신인 쪽이 이긴다(백필이 과거 페이지를
 * 훑다가 라이브가 넣은 최신 캡션을 되돌리지 않게 하는 장치).
 *
 * <p>JPA 엔티티를 두지 않고 JdbcTemplate 배치를 쓰는 이유: 읽는 코드가 없어(조회는 SQL 직접)
 * 엔티티의 값이 없고, 백필이 약 15만 행을 넣어야 해 행당 왕복이 비싸다.
 */
@Service
public class ContentCaptionUpserter {

    private static final String UPSERT = """
            INSERT INTO content_caption(content_id, caption, source, captured_at, updated_at)
            VALUES (?, ?, ?, ?, now())
            ON CONFLICT (content_id) DO UPDATE
               SET caption = EXCLUDED.caption, source = EXCLUDED.source,
                   captured_at = EXCLUDED.captured_at, updated_at = now()
             WHERE content_caption.captured_at <= EXCLUDED.captured_at
            """;

    private final ContentRepository contents;
    private final JdbcTemplate jdbc;

    public ContentCaptionUpserter(ContentRepository contents, JdbcTemplate jdbc) {
        this.contents = contents;
        this.jdbc = jdbc;
    }

    /**
     * 적재를 시도한 행 수를 반환한다(content 행이 없어 건너뛴 것·캡션이 미확인(null)이라
     * 건너뛴 것은 제외).
     * 호출자 트랜잭션에 합류한다(JdbcTemplate이 DataSourceUtils로 스레드 바운드 커넥션을 공유) —
     * 이 메서드 자체는 트랜잭션 경계를 열지 않는다.
     *
     * <p>같은 short_code가 배치에 중복되면 마지막 것만 남긴다 — 결과를 드라이버의 배치 재작성
     * 설정에 의존시키지 않기 위함이다. reWriteBatchedInserts=true면 배치가 하나의 다중행
     * INSERT로 합쳐져 같은 충돌 대상이 두 번 나오는 순간 Postgres가 실패시킨다.
     */
    public int upsert(Collection<MediaItem> items, RawSource source, Instant capturedAt) {
        if (items.isEmpty()) return 0;
        Map<String, MediaItem> byShortCode = new LinkedHashMap<>();
        for (MediaItem it : items) byShortCode.put(it.shortCode(), it);

        Map<String, Content> found = contents.findByShortCodeIn(byShortCode.keySet()).stream()
                .collect(Collectors.toMap(Content::getShortCode, Function.identity()));

        List<Object[]> batch = new ArrayList<>();
        for (MediaItem it : byShortCode.values()) {
            // 미확인(null) 캡션은 배치에서 제외한다 — 이 값을 그대로 넣으면 이 payload 형태에서
            // 캡션을 못 읽었을 뿐인데도 실제로 확보된 캡션(다른 소스 페이지가 이미 넣은 값)을
            // ""로 덮어쓰게 된다(MediaItem.caption 3-상태 계약 참고).
            if (it.caption() == null) continue;
            Content c = found.get(it.shortCode());
            if (c == null) continue;   // 열거 창 밖 등으로 content가 없는 경우 — 조용히 건너뛴다
            batch.add(new Object[] {
                    c.getId(), it.caption(), source.name(), Timestamp.from(capturedAt) });
        }
        if (batch.isEmpty()) return 0;
        jdbc.batchUpdate(UPSERT, batch);
        return batch.size();
    }
}
