package com.celfit.crawler.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.FakeApifyRunner;
import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.domain.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

@Import(AggregateJobTest.Config.class)
@Transactional
class AggregateJobTest extends IntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean @Primary
        FakeApifyRunner fakeApifyRunner() {
            return new FakeApifyRunner();
        }
    }

    @Autowired FakeApifyRunner fake;
    @Autowired AggregateJob job;

    @org.junit.jupiter.api.BeforeEach
    void resetFake() {
        fake.reset();
    }

    @Autowired CategoryRepository categories;
    @Autowired ContentRepository contents;
    @Autowired RawPostDetailRepository rawDetails;
    @Autowired RawCommentRepository rawComments;

    Long catId;

    Content seedQualified(String shortCode, int daysAgo) {
        if (catId == null) catId = categories.save(new Category("메이크업")).getId();
        Content c = new Content(shortCode, ContentType.REELS, "kim",
                Instant.now().minus(daysAgo, ChronoUnit.DAYS), catId, "메이크업", Instant.now());
        c.setStatus(ContentStatus.QUALIFIED);
        return contents.save(c);
    }

    static Map<String, Object> detail(String shortCode) {
        return Map.of("shortCode", shortCode, "likesCount", 10, "commentsCount", 2);
    }

    static Map<String, Object> comment(String shortCode, String text) {
        return Map.of("postUrl", "https://www.instagram.com/p/" + shortCode + "/",
                "ownerUsername", "fan", "text", text, "timestamp", "2026-07-05T00:00:00.000Z");
    }

    @Test
    void 도래분은_상세와_댓글을_적재하고_AGGREGATED가_된다() {
        seedQualified("sc1", 4);
        seedQualified("fresh", 1);  // 아직 3일 안 됨 — 대상 아님
        fake.enqueue(List.of(detail("sc1")));
        fake.enqueue(List.of(comment("sc1", "굿"), comment("sc1", "최고")));

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.aggregated()).isEqualTo(1);
        Content c = contents.findByShortCode("sc1").orElseThrow();
        assertThat(c.getStatus()).isEqualTo(ContentStatus.AGGREGATED);
        assertThat(c.getAggregatedAt()).isNotNull();
        assertThat(rawDetails.count()).isEqualTo(1);
        assertThat(rawComments.count()).isEqualTo(2);
        assertThat(contents.findByShortCode("fresh").orElseThrow().getStatus())
                .isEqualTo(ContentStatus.QUALIFIED);
        // 댓글 액터 입력에 게시물당 상한이 들어간다
        assertThat(fake.calls.get(1).input()).containsEntry("resultsLimit", 50);
    }

    @Test
    void 응답에_없는_shortcode는_GONE() {
        seedQualified("살아있음", 4);
        seedQualified("삭제됨", 4);
        fake.enqueue(List.of(detail("살아있음")));
        fake.enqueue(List.of());  // 댓글 없음

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.aggregated()).isEqualTo(1);
        assertThat(summary.gone()).isEqualTo(1);
        assertThat(contents.findByShortCode("삭제됨").orElseThrow().getStatus())
                .isEqualTo(ContentStatus.GONE);
    }

    @Test
    void 액터_실패시_attempts_증가하고_상한_도달하면_FAILED() {
        Content c = seedQualified("sc1", 4);
        fake.enqueueFailure("일시 장애");

        var first = job.run(TriggerType.MANUAL);
        assertThat(first.retried()).isEqualTo(1);
        assertThat(contents.findById(c.getId()).orElseThrow().getAggregateAttempts()).isEqualTo(1);
        assertThat(contents.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(ContentStatus.QUALIFIED);

        // max-attempts=3 (테스트 yml) — 두 번 더 실패하면 FAILED
        fake.enqueueFailure("또 장애");
        job.run(TriggerType.MANUAL);
        fake.enqueueFailure("계속 장애");
        var third = job.run(TriggerType.MANUAL);

        assertThat(third.failed()).isEqualTo(1);
        assertThat(contents.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(ContentStatus.FAILED);
    }

    @Test
    void detail_응답이_완전히_비면_GONE이_아니라_재시도로_처리된다() {
        Content c = seedQualified("sc1", 4);
        fake.enqueue(List.of());   // detail: 빈 응답 — 액터 소프트 실패(레이트리밋 등) 가능성

        var summary = job.run(TriggerType.MANUAL);

        assertThat(summary.gone()).isZero();
        assertThat(summary.retried()).isEqualTo(1);
        assertThat(contents.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(ContentStatus.QUALIFIED);
        assertThat(contents.findById(c.getId()).orElseThrow().getAggregateAttempts()).isEqualTo(1);
        // 빈 응답 가드가 댓글 액터 호출 전에 걸려 호출 자체를 아낀다
        assertThat(fake.calls).hasSize(1);
    }
}
