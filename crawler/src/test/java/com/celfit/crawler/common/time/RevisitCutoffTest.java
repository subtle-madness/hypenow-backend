package com.celfit.crawler.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * 재방문 경계 — "마지막 방문 후 N일 경과"가 아니라 달력일 기준이다.
 * N=1이면 오늘(클록 존) 자정: 어제 방문한 계정은 시각과 무관하게 오늘 다시 대상이 된다.
 */
class RevisitCutoffTest {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 2026-07-18(토) 10:00 KST에 잡이 돈다고 가정
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-18T01:00:00Z"), KST);

    @Test
    void 주기_1일이면_오늘_자정이_경계다() {
        assertThat(RevisitCutoff.boundary(CLOCK, 1))
                .isEqualTo(Instant.parse("2026-07-17T15:00:00Z"));  // 2026-07-18 00:00 KST
    }

    @Test
    void 어제_오후_방문은_다시_대상이_되고_오늘_새벽_방문은_제외된다() {
        // 선정 쿼리는 lastCollectedAt < boundary — 어제 15:00 방문은 경계 이전(대상),
        // 오늘 01:00 방문은 경계 이후(제외)여야 "오늘 하루치" 의미가 맞다.
        Instant boundary = RevisitCutoff.boundary(CLOCK, 1);
        Instant yesterdayAfternoon = ZonedDateTime.of(2026, 7, 17, 15, 0, 0, 0, KST).toInstant();
        Instant todayEarlyMorning = ZonedDateTime.of(2026, 7, 18, 1, 0, 0, 0, KST).toInstant();
        assertThat(yesterdayAfternoon).isBefore(boundary);
        assertThat(todayEarlyMorning).isAfter(boundary);
    }

    @Test
    void 주기_N일이면_N마이너스1일_전_자정이_경계다() {
        // N=3: 오늘 포함 3일 창 — 마지막 방문이 그저께 자정 이전이어야 대상
        assertThat(RevisitCutoff.boundary(CLOCK, 3))
                .isEqualTo(Instant.parse("2026-07-15T15:00:00Z"));  // 2026-07-16 00:00 KST
    }
}
