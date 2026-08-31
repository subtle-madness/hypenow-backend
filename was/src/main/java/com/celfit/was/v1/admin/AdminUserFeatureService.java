package com.celfit.was.v1.admin;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 유저별 기능 플래그 교체 + 감사 기록(2026-08-31) — PUT /v1/admin/users/{id}/features의 쓰기 몸통.
 *
 * <p>둘을 한 트랜잭션에 묶는다. {@link com.celfit.was.security.ActAsUserFilter}의 감사 기록은
 * 조회를 막지 않으려 best-effort지만, 여기는 <b>상태를 바꾸는 요청</b>이라 "기록 없는 변경"이
 * 남으면 감사 로그 자체가 신뢰를 잃는다 — 기록이 실패하면 변경도 함께 롤백한다.
 */
@Service
public class AdminUserFeatureService {

	private final AdminUserRepository userRepository;
	private final AdminAuditLogRepository auditLogRepository;

	public AdminUserFeatureService(AdminUserRepository userRepository,
			AdminAuditLogRepository auditLogRepository) {
		this.userRepository = userRepository;
		this.auditLogRepository = auditLogRepository;
	}

	/**
	 * 전체 교체(병합 아님). 대상 유저가 없으면 빈 Optional — 그 경우 감사 기록도 남기지 않는다
	 * (일어나지 않은 변경이라).
	 *
	 * @return DB에 저장된 jsonb 원문(::text)
	 */
	@Transactional
	public Optional<String> replaceOverrides(long adminId, long targetUserId, String overridesJson, String path) {
		Optional<String> stored = userRepository.updateFeatureOverrides(targetUserId, overridesJson);
		stored.ifPresent(ignored -> auditLogRepository.insert(adminId, targetUserId, path));
		return stored;
	}
}
