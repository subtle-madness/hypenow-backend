package com.celfit.was.v1.admin;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 감사 로그 90일 보존 배치(어드민 백엔드 API 설계 2026-08-01 §3, A8) — 매일 1회 90일 경과분을
 * 전량 삭제한다(묶음 기록 없이 기록했으므로 삭제도 단순 cutoff 기준).
 * {@link com.celfit.was.v1.monitoring.RecoverStalePendingScheduler} 관용구(단일 책임 @Scheduled 컴포넌트) 참고.
 */
@Component
public class AdminAuditLogRetentionScheduler {

	private static final Logger log = LoggerFactory.getLogger(AdminAuditLogRetentionScheduler.class);

	private static final int RETENTION_DAYS = 90;

	private final AdminAuditLogRepository repository;
	private final Clock clock;

	public AdminAuditLogRetentionScheduler(AdminAuditLogRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Scheduled(cron = "${admin.audit-log.retention.cron:0 30 3 * * *}", zone = "UTC")
	public void deleteExpired() {
		OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(RETENTION_DAYS);
		try {
			int deleted = repository.deleteOlderThan(cutoff);
			log.info("어드민 감사 로그 보존 삭제 — cutoff={}, 삭제 건수={}", cutoff, deleted);
		} catch (RuntimeException e) {
			log.error("어드민 감사 로그 보존 삭제 실패 — cutoff={}", cutoff, e);
		}
	}
}
