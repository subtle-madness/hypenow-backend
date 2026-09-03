package com.celfit.monitoring.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 브랜드 태그 스윕 스케줄 — 기본 "-"(비활성). 운영은 env로 UTC 크론 주입(KST 02:00 = 0 0 17 * * *).
 * 설계 원안은 캠페인 스윕(KST 02:00)과 시차를 둔 03:00이었으나 서버 override가 02:00으로 운영돼 왔고
 * 08-12에 02:00을 정본으로 수용했다(동시 실행). 표기용 {@code was.brand.sweep-hour-kst}와 함께 움직인다.
 *
 * <p>가드는 {@link BrandSweepGuard}(2026-09 열거 실패 재시도 스케줄러 신설로 내부 private
 * {@code AtomicBoolean}에서 공유 컴포넌트로 추출 — 동작은 동일하다) — 전날 스윕이 밀려 다음 틱과
 * 겹치는 경우를 막던 원래 역할에 더해, 이제 {@link BrandBackfillRetryJob}도 이 가드를 봐서 야간
 * 스윕 중에는 재시도 틱을 스킵한다.
 */
@Component
public class BrandSweepScheduler {

	private static final Logger log = LoggerFactory.getLogger(BrandSweepScheduler.class);

	private final BrandSweepJob job;
	private final BrandSweepGuard guard;

	public BrandSweepScheduler(BrandSweepJob job, BrandSweepGuard guard) {
		this.job = job;
		this.guard = guard;
	}

	@Scheduled(cron = "${monitoring.brand.schedule.sweep-cron:-}", zone = "UTC")
	public void sweep() {
		if (!guard.tryAcquire()) {
			log.warn("브랜드 스윕 스킵 — 이미 실행 중(이전 스윕 미종료)");
			return;
		}
		try {
			job.run();
		} finally {
			guard.release();
		}
	}
}
