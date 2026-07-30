package com.celfit.monitoring.service;

import com.celfit.monitoring.domain.CandidateStatus;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.store.CandidateRepository;
import com.celfit.monitoring.store.CandidateRow;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.store.TargetRow;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 등록 이후의 캠페인 명령 4종 — 승인·거절·연장·해지(계약 §2-2~2-5).
 * 상태 전이 규칙이 여기 모여 있다: 승인은 WATCHING에서만, 연장은 활성에서만, 해지는 멱등.
 */
@Service
public class TargetCommandService {

	private final TargetRepository targets;
	private final CandidateRepository candidates;
	private final CollectService collectService;

	public TargetCommandService(TargetRepository targets, CandidateRepository candidates,
			CollectService collectService) {
		this.targets = targets;
		this.candidates = candidates;
		this.collectService = collectService;
	}

	/**
	 * 후보 승인 — 그 게시물로 TRACKING 전환 + 즉시 1회 수집.
	 * 수집을 지금 하는 이유: 다음 02:00 스윕까지 기다리면 승인 직후 화면에 지표가 하나도 없다.
	 */
	@Transactional
	public TargetRow approve(long targetId, long candidateId) {
		TargetRow target = targets.findById(targetId)
				.orElseThrow(() -> new TargetNotFoundException("target 없음: " + targetId));
		// TRACKING에서 또 승인하면 추적 대상이 조용히 갈아치워진다 — WATCHING에서만 허용.
		if (target.status() != TargetStatus.WATCHING) {
			throw new InvalidStateException("WATCHING 상태에서만 승인 가능: " + target.status());
		}
		CandidateRow candidate = requirePendingCandidate(targetId, candidateId, "승인");
		candidates.setStatus(candidateId, CandidateStatus.APPROVED);
		targets.markTracking(targetId, candidate.shortCode());
		collectService.collectPost(candidate.shortCode());   // 승인 즉시 1회 수집
		targets.touchFetched(targetId);
		return targets.findById(targetId).orElseThrow();
	}

	/** 후보 기각 — 후보만 닫고 캠페인은 그대로 감시를 지속한다(수집 호출 없음). */
	@Transactional
	public CandidateRow reject(long targetId, long candidateId) {
		TargetRow target = targets.findById(targetId)
				.orElseThrow(() -> new TargetNotFoundException("target 없음: " + targetId));
		if (!target.status().active()) {
			throw new InvalidStateException("종결된 캠페인의 후보는 검토할 수 없습니다: " + target.status());
		}
		CandidateRow candidate = requirePendingCandidate(targetId, candidateId, "기각");
		candidates.setStatus(candidateId, CandidateStatus.REJECTED);
		return candidates.find(candidateId).orElseThrow();   // 응답은 항상 저장된 값으로 만든다
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
	 * 해지 — CANCELED로 전이(행·스냅샷 보존). 이미 종결이면 현재 상태 그대로 돌려준다(계약 §2-5 멱등).
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

	/** 후보 조회 + 소속·상태 검사. 남의 캠페인 후보 id는 "없음"으로 본다(존재 여부를 흘리지 않는다). */
	private CandidateRow requirePendingCandidate(long targetId, long candidateId, String action) {
		CandidateRow candidate = candidates.find(candidateId)
				.filter(c -> c.targetId() == targetId)
				.orElseThrow(() -> new CandidateNotFoundException("후보 없음: " + candidateId));
		if (candidate.status() != CandidateStatus.PENDING) {
			throw new InvalidStateException("PENDING 후보만 " + action + " 가능: " + candidate.status());
		}
		return candidate;
	}
}
