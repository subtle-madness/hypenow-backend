package com.celfit.was.v1.account;

import com.celfit.was.auth.UserRepository;
import com.celfit.was.monitoring.MonitoringCommandClient;
import com.celfit.was.monitoring.MonitoringItemRepository;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 탈퇴(스펙 6.13) DB 삭제 전 모니터링 target 해지 루프(프론트 계약 4절 31번, 갭 문서 B-2) — 모니터링은
 * 탈퇴 후에도 서버가 계속 일하는 유일한 기능이다. app 데이터 5종은 CASCADE(V16)로 함께 지워지지만,
 * monitoring 서브시스템의 target은 was가 명시적으로 해지 API(DELETE /api/targets/{id})를 불러야
 * 배치(크롤·알람) 대상에서 즉시 빠진다. CASCADE로 monitoring_items 행이 사라지기 전에 target_id
 * 목록을 먼저 확보해야 하므로 해지 → DB 삭제 순서가 고정이다.
 *
 * <p>V13 시절 결정 계승 — 해지가 실패해도 탈퇴는 진행한다(fail-open): 고아 target은 expires_at
 * 자연 만료로 유계이지만, 탈퇴 자체를 막는 게 더 나쁘다. 행 단위로 격리해(한 건 실패가 나머지·
 * 탈퇴를 막지 않음) 실패는 로그만 남긴다. monitoring.enabled=false(MonitoringConfig 조건부 —
 * 빈 자체가 없음)면 루프를 통째로 스킵한다(V1MonitoringItemUpdateService의 Optional 주입 관용구
 * 재사용).
 */
@Service
public class AccountDeletionService {

	private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

	private final UserRepository userRepository;
	private final MonitoringItemRepository monitoringItemRepository;
	private final Optional<MonitoringCommandClient> monitoringCommandClient;

	public AccountDeletionService(UserRepository userRepository,
			MonitoringItemRepository monitoringItemRepository,
			Optional<MonitoringCommandClient> monitoringCommandClient) {
		this.userRepository = userRepository;
		this.monitoringItemRepository = monitoringItemRepository;
		this.monitoringCommandClient = monitoringCommandClient;
	}

	public void deleteAccount(long userId) {
		cancelActiveMonitoring(userId);
		userRepository.deleteAccount(userId);
	}

	private void cancelActiveMonitoring(long userId) {
		if (monitoringCommandClient.isEmpty()) {
			return;   // monitoring.enabled=false — 서브시스템 자체가 없다
		}
		MonitoringCommandClient client = monitoringCommandClient.get();
		List<Long> targetIds = monitoringItemRepository.findActiveTargetIds(userId);
		for (Long targetId : targetIds) {
			try {
				client.cancel(targetId);
			} catch (RuntimeException e) {
				log.warn("탈퇴 시 모니터링 target 해지 실패 — 탈퇴는 계속 진행(고아 target은 자연 만료). userId={}, targetId={}",
						userId, targetId, e);
			}
		}
	}
}
