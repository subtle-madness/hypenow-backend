package com.celfit.monitoring.service;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 태그별 스윕 실행 상태 판정(FE 요청, 2026-08-31) — {@code brand_hashtag}의 실행 컬럼
 * (last_run_started_at·last_run_finished_at·last_run_found_count·last_run_failed)에서
 * FE 폴링용 status(collecting|done|failed)를 <b>읽을 때마다 계산</b>한다(저장 안 함 — 저장하면
 * "지금 실행 중" 여부가 별도 컬럼과 어긋날 수 있는 이중 진실 문제가 생긴다).
 *
 * <p>판정 규칙(설계 순서대로):
 * <ol>
 *   <li>한 번도 안 돔(started_at·finished_at 둘 다 NULL) — collecting, lastRunAt=null,
 *       lastFoundCount=null. 등록 직후 대기도 이 경로다(FE 계약상 폴링 지속이 맞다).</li>
 *   <li>실행 중(started_at IS NOT NULL AND (finished_at IS NULL OR started_at &gt; finished_at)) —
 *       collecting. 단 <b>크래시 잔류 방지</b>: started_at이 {@link #STALE_THRESHOLD}(10분) 이상
 *       과거면 in-flight로 보지 않고 이전 종료 상태로 폴백한다(이전 finished가 없으면 failed —
 *       첫 실행 자체가 크래시로 끝난 것).</li>
 *   <li>그 외(정상 종료, 또는 위 폴백 도달) — failed=true면 failed, 아니면 done.
 *       lastRunAt=finished_at, lastFoundCount=found_count.</li>
 * </ol>
 *
 * <p>lastRunAt·lastFoundCount는 status 분기와 무관하게 <b>항상 finished_at·found_count를 그대로
 * 노출</b>한다 — collecting(실행 중)이어도 직전 완료분을 같이 보여주는 편이 FE에 더 유용하고,
 * 별도 규칙 없이도 위 세 분기 전부와 일관된다(1번은 finished_at이 애초에 null).
 */
public final class BrandHashtagRunStateResolver {

	private static final Duration STALE_THRESHOLD = Duration.ofMinutes(10);

	public static final String COLLECTING = "collecting";
	public static final String DONE = "done";
	public static final String FAILED = "failed";

	private BrandHashtagRunStateResolver() {
	}

	public record RunState(String status, OffsetDateTime lastRunAt, Integer lastFoundCount) {
	}

	public static RunState resolve(OffsetDateTime startedAt, OffsetDateTime finishedAt, Integer foundCount,
			boolean failed, OffsetDateTime now) {
		if (startedAt == null && finishedAt == null) {
			return new RunState(COLLECTING, null, null);
		}
		boolean inFlight = startedAt != null && (finishedAt == null || startedAt.isAfter(finishedAt));
		if (inFlight && !isStale(startedAt, now)) {
			return new RunState(COLLECTING, finishedAt, foundCount);
		}
		if (inFlight && finishedAt == null) {
			// 크래시 잔류: 첫 실행 자체가 시작만 기록되고 끝나지 못했다 — 이전 종료 상태가 없다.
			return new RunState(FAILED, null, null);
		}
		return new RunState(failed ? FAILED : DONE, finishedAt, foundCount);
	}

	private static boolean isStale(OffsetDateTime startedAt, OffsetDateTime now) {
		return Duration.between(startedAt, now).compareTo(STALE_THRESHOLD) >= 0;
	}
}
