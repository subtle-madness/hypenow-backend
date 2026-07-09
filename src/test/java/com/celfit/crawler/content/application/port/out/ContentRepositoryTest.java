package com.celfit.crawler.content.application.port.out;

import com.celfit.crawler.content.domain.*;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ContentRepositoryTest extends IntegrationTest {

    @Autowired ContentRepository contents;
    @Autowired CategoryRepository categories;

    static final Instant CUTOFF = Instant.parse("2026-07-04T00:00:00Z");

    Long catId;

    Content save(String shortCode, ContentStatus status, Instant uploadedAt) {
        if (catId == null) catId = categories.save(new Category("메이크업")).getId();
        Content c = new Content(shortCode, ContentType.REELS, "user_" + shortCode,
                uploadedAt, catId, "메이크업", Instant.parse("2026-07-01T00:00:00Z"));
        c.setStatus(status);
        return contents.save(c);
    }

    @Test
    void shortCode로_조회된다() {
        save("sc1", ContentStatus.PENDING, CUTOFF);
        assertThat(contents.findByShortCode("sc1")).isPresent();
        assertThat(contents.findByShortCode("없음")).isEmpty();
    }

    @Test
    void findDue는_QUALIFIED이고_미집계이고_컷오프_이전_업로드만_고른다() {
        save("due1", ContentStatus.QUALIFIED, CUTOFF.minusSeconds(3600));  // 대상
        save("due2", ContentStatus.QUALIFIED, CUTOFF);                      // 경계 = 대상 (<=)
        save("fresh", ContentStatus.QUALIFIED, CUTOFF.plusSeconds(3600));   // 아직 3일 안 됨
        save("pend", ContentStatus.PENDING, CUTOFF.minusSeconds(3600));     // 미판정
        Content done = save("done", ContentStatus.AGGREGATED, CUTOFF.minusSeconds(3600));
        done.setAggregatedAt(Instant.now());
        // status는 QUALIFIED지만 이미 집계됨 — aggregatedAt is null 조건이 빠지면 잘못 포함된다
        Content doneButQualified = save("qdone", ContentStatus.QUALIFIED, CUTOFF.minusSeconds(3600));
        doneButQualified.setAggregatedAt(Instant.now());

        var due = contents.findDue(ContentStatus.QUALIFIED, CUTOFF, PageRequest.of(0, 10));
        assertThat(due).extracting(Content::getShortCode).containsExactly("due1", "due2");
    }

    @Test
    void findDue는_배치_상한을_지킨다() {
        save("a", ContentStatus.QUALIFIED, CUTOFF.minusSeconds(30));
        save("b", ContentStatus.QUALIFIED, CUTOFF.minusSeconds(20));
        save("c", ContentStatus.QUALIFIED, CUTOFF.minusSeconds(10));
        var due = contents.findDue(ContentStatus.QUALIFIED, CUTOFF, PageRequest.of(0, 2));
        assertThat(due).hasSize(2);
    }
}
