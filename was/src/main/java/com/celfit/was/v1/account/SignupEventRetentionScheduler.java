package com.celfit.was.v1.account;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 가입 시도 이벤트 90일 보존 배치(트랙 A 스펙 §signup_events) — 비회원 이메일 포함 로그라
 * 목적(디버깅·어뷰징 추적) 소멸분은 파기한다(암호화와 별개의 법적 파기 의무).
 * AdminAuditLogRetentionScheduler 관용구.
 */
@Component
public class SignupEventRetentionScheduler {

	private static final Logger log = LoggerFactory.getLogger(SignupEventRetentionScheduler.class);
	private static final int RETENTION_DAYS = 90;

	private final JdbcClient jdbcClient;
	private final Clock clock;

	public SignupEventRetentionScheduler(JdbcClient jdbcClient, Clock clock) {
		this.jdbcClient = jdbcClient;
		this.clock = clock;
	}

	@Scheduled(cron = "${signup-events.retention.cron:0 40 3 * * *}", zone = "UTC")
	public void deleteExpired() {
		OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(RETENTION_DAYS);
		try {
			int deleted = jdbcClient.sql("DELETE FROM app.signup_events WHERE created_at < :cutoff")
					.param("cutoff", cutoff)
					.update();
			log.info("가입 이벤트 보존 삭제 — cutoff={}, 삭제 건수={}", cutoff, deleted);
		} catch (RuntimeException e) {
			log.error("가입 이벤트 보존 삭제 실패 — cutoff={}", cutoff, e);
		}
	}
}
