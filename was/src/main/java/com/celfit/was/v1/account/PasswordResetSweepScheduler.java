package com.celfit.was.v1.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * app.password_resets 일일 만료 스윕 — 행 삭제 경로가 reset 성공(claim) 하나뿐이라, 코드만 받고
 * 이탈하거나 이메일을 오타 입력한 미완주 행이 무기한 잔존한다. 탈퇴 유저의 이메일(평문 PII)도
 * 같은 표로 걸린다.
 *
 * <p><b>탈퇴 즉시 삭제 캐스케이드는 두지 않는다</b> — 이 스윕이 만료 후 최대 ~1일 내에 삭제하므로
 * 탈퇴 유저가 남긴 행도 별도 배선 없이 커버된다(2026-08-12 결정, 재논의 대상 아님).
 *
 * <p>삭제 조건은 {@link PasswordResetRepository#deleteExpired()} 참조 — 코드·토큰이 모두 만료된
 * 지 유예 1일이 지난 행만 지운다. 유예 1일은 만료 직후 재시도하는 유저의 attempts 카운터를
 * 살려두는 여유이자 디버깅 창이다.
 *
 * <p>{@link com.celfit.was.v1.admin.AdminAuditLogRetentionScheduler} 관용구(cron 기본값 +
 * 프로퍼티 오버라이드, 단일 책임 @Scheduled 컴포넌트) 참고. 크론은 다른 스케줄러들과 겹치지 않는
 * 새벽대(UTC 03:40, 감사 로그 보존 03:30 다음)로 잡았다.
 */
@Component
public class PasswordResetSweepScheduler {

	private static final Logger log = LoggerFactory.getLogger(PasswordResetSweepScheduler.class);

	private final PasswordResetRepository repository;

	public PasswordResetSweepScheduler(PasswordResetRepository repository) {
		this.repository = repository;
	}

	@Scheduled(cron = "${was.password-reset.sweep.cron:0 40 3 * * *}", zone = "UTC")
	public void sweep() {
		try {
			int deleted = repository.deleteExpired();
			log.info("비밀번호 재설정 만료 행 스윕 — 삭제 건수={}", deleted);
		} catch (RuntimeException e) {
			log.error("비밀번호 재설정 만료 행 스윕 실패", e);
		}
	}
}
