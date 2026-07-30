package com.celfit.monitoring.service;

import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.store.TargetRow;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 등록 이후의 캠페인 명령 2종 — 연장·해지(계약 v2 §2-2~2-3).
 * 승인·기각은 v2에서 폐지됐다: 감지되면 스윕이 바로 추적으로 전환한다({@link DailySweepJob}).
 * 남은 상태 전이 규칙은 둘이다 — 연장은 활성에서만, 해지는 멱등.
 */
@Service
public class TargetCommandService {

	private final TargetRepository targets;

	public TargetCommandService(TargetRepository targets) {
		this.targets = targets;
	}

	/** 기간 연장 — 활성 캠페인만. 종결분을 되살리는 경로는 없다(재등록이 정답). */
	@Transactional
	public TargetRow extend(long targetId, Instant expiresAt) {
		if (expiresAt == null) {
			throw new ValidationException("expiresAt은 필수입니다.");
		}
		if (!expiresAt.isAfter(Instant.now())) {
			// 과거로의 연장은 다음 스윕에서 즉시 EXPIRED가 된다 — 의도한 명령일 리 없다.
			throw new ValidationException("expiresAt은 미래 시각이어야 합니다.");
		}
		TargetRow target = targets.findById(targetId)
				.orElseThrow(() -> new TargetNotFoundException("target 없음: " + targetId));
		if (!target.status().active()) {
			throw new InvalidStateException("활성 캠페인만 연장할 수 있습니다: " + target.status());
		}
		targets.updateExpiresAt(targetId, expiresAt);
		return targets.findById(targetId).orElseThrow();
	}

	/**
	 * 해지 — CANCELED로 전이(행·스냅샷 보존). 이미 종결이면 현재 상태 그대로 돌려준다(계약 §2-3 멱등).
	 * 멱등이어야 하는 이유: was의 타임아웃 재시도가 409로 튕기면 사용자에게 해지 실패로 보인다.
	 */
	@Transactional
	public TargetRow cancel(long targetId) {
		TargetRow target = targets.findById(targetId)
				.orElseThrow(() -> new TargetNotFoundException("target 없음: " + targetId));
		if (!target.status().active()) {
			return target;   // closed_at도 덮어쓰지 않는다 — 종결 시점이 재시도로 뒤로 밀리면 안 된다
		}
		targets.close(targetId, TargetStatus.CANCELED, null);
		return targets.findById(targetId).orElseThrow();
	}
}
