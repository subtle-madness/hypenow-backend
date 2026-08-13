package com.celfit.monitoring.service;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 브랜드 태그 스윕 스케줄 — 기본 "-"(비활성). 운영은 env로 UTC 크론 주입(KST 02:00 = 0 0 17 * * *).
 * 설계 원안은 캠페인 스윕(KST 02:00)과 시차를 둔 03:00이었으나 서버 override가 02:00으로 운영돼 왔고
 * 08-12에 02:00을 정본으로 수용했다(동시 실행). 표기용 {@code was.brand.sweep-hour-kst}와 함께 움직인다.
 *
 * <p>수동 트리거가 없어 캠페인의 공유 SweepGuard 대신 내부 가드만 둔다 — 전날 스윕이
 * 밀려 다음 틱과 겹치는 경우만 막으면 된다(겹치면 이번 틱은 건너뛰고 다음날 자연 재시도).
 */
@Component
public class BrandSweepScheduler {

	private static final Logger log = LoggerFactory.getLogger(BrandSweepScheduler.class);

	private final BrandSweepJob job;
	private final AtomicBoolean running = new AtomicBoolean(false);

	public BrandSweepScheduler(BrandSweepJob job) {
		this.job = job;
	}

	@Scheduled(cron = "${monitoring.brand.schedule.sweep-cron:-}", zone = "UTC")
	public void sweep() {
		if (!running.compareAndSet(false, true)) {
			log.warn("브랜드 스윕 스킵 — 이미 실행 중(이전 스윕 미종료)");
			return;
		}
		try {
			job.run();
		} finally {
			running.set(false);
		}
	}
}
