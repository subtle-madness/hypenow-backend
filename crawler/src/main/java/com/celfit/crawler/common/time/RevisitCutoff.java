package com.celfit.crawler.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 재방문 경계 계산 — 달력일 기준. 마지막 방문이 이 경계 이전이면 다시 수집 대상이다.
 * N=1이면 오늘(클록 존) 자정: 어제 방문한 계정은 방문 시각과 무관하게 오늘 다시 대상이 되고,
 * 자정에 전원 자동 리셋된다. N일 주기는 "오늘 포함 N일 창"으로 해석한다(경계 = 자정 − (N−1)일).
 * collect/reels 선정과 대시보드 due 수치가 모두 이 값을 공유해야 화면과 실제 대상이 일치한다.
 */
public final class RevisitCutoff {

    private RevisitCutoff() {}

    public static Instant boundary(Clock clock, int revisitIntervalDays) {
        return LocalDate.now(clock).minusDays(revisitIntervalDays - 1L)
                .atStartOfDay(clock.getZone()).toInstant();
    }
}
