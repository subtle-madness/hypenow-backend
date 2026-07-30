package com.celfit.monitoring.service;

import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.PrivateAccountException;
import com.celfit.monitoring.hiker.SubjectNotFoundException;
import com.celfit.monitoring.store.CandidateRepository;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.store.TargetRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 일일 스윕(KST 02:00) — 만료 처리 → 계정별 1회 수집(캠페인 수와 무관) →
 * WATCHING 키워드 감지 → TRACKING 게시물 보강. 실패는 계정·캠페인 단위로 격리한다.
 *
 * <p>트랜잭션을 걸지 않는다: 스윕 한 번은 계정 수만큼 외부 콜을 돌기 때문에
 * 전체를 한 트랜잭션으로 묶으면 커넥션을 몇 분씩 잡고, 마지막 계정의 실패가 앞선 전 계정의
 * 수집을 되돌린다. 커밋 단위는 수집 1회({@link SnapshotWriter})와 상태 전이 1건이다.
 */
@Service
public class DailySweepJob {

	private static final Logger log = LoggerFactory.getLogger(DailySweepJob.class);
	private static final int EXCERPT_LEN = 120;
	private static final String NOT_FOUND = "SUBJECT_NOT_FOUND";
	private static final String PRIVATE_ACCOUNT = "PRIVATE_ACCOUNT";

	private final TargetRepository targets;
	private final CandidateRepository candidates;
	private final CollectService collect;

	public DailySweepJob(TargetRepository targets, CandidateRepository candidates,
			CollectService collect) {
		this.targets = targets;
		this.candidates = candidates;
		this.collect = collect;
	}

	public void run() {
		// 만료를 먼저 닫아야 만기 지난 캠페인이 그날 스윕 대상에서 빠진다 — 순서가 바뀌면 종료된 캠페인만큼 콜이 샌다.
		int expired = targets.expireOverdue();
		Map<String, List<TargetRow>> byUsername = targets.findActive().stream()
				.collect(Collectors.groupingBy(TargetRow::username));
		int failedAccounts = 0;
		for (var entry : byUsername.entrySet()) {
			try {
				sweepAccount(entry.getKey(), entry.getValue());
			} catch (SubjectNotFoundException e) {
				// 계정 자체가 없어졌다(삭제·개명) — 재시도해도 결과가 같으니 그 계정의 캠페인을 전부 종결한다.
				closeAll(entry.getKey(), entry.getValue(), NOT_FOUND);
				failedAccounts++;
			} catch (PrivateAccountException e) {
				// 비공개 전환도 결정적 수집 불가다(설계 §5 "계정 소멸·비공개 등 → FAILED").
				// 일반 실패로 두면 만료일까지 매일 1콜을 태우면서 영원히 WATCHING으로 남는다.
				closeAll(entry.getKey(), entry.getValue(), PRIVATE_ACCOUNT);
				failedAccounts++;
			} catch (RuntimeException e) {
				// 재시도 여지가 있는 실패(5xx·타임아웃·셰이프 이상)는 상태를 건드리지 않는다 — 내일 스윕이 다시 본다.
				log.warn("스윕 실패(격리) — 계정 {}: {}", entry.getKey(), e.toString());
				failedAccounts++;
			}
		}
		log.info("스윕 완료 — 계정 {}건(실패 {}), 만료 {}건", byUsername.size(), failedAccounts, expired);
	}

	/**
	 * 계정 1개분 — 열거는 캠페인 수와 무관하게 한 번만 하고, 그 결과를 캠페인들이 나눠 본다.
	 * 여기서 던지는 예외는 계정 전체의 실패다(호출자가 종결 판단).
	 */
	private void sweepAccount(String username, List<TargetRow> accountTargets) {
		// POST 등록분만 있는 계정은 열거할 이유가 없다 — 프로필·열거 2~3콜이 통째로 낭비된다.
		List<PostInfo> posts = needsEnumeration(accountTargets)
				? collect.collectAccount(username).posts()
				: List.of();
		Set<String> enumerated = posts.stream().map(PostInfo::shortCode).collect(Collectors.toSet());
		for (TargetRow t : accountTargets) {
			try {
				sweepTarget(t, posts, enumerated);
			} catch (SubjectNotFoundException e) {
				// 추적 게시물만 삭제된 경우 — 계정은 멀쩡하니 이 캠페인 하나만 종결한다.
				log.info("추적 게시물 부재 — 캠페인 {} 종결: {}", t.id(), t.trackedShortCode());
				closeFailed(t.id(), NOT_FOUND);
			} catch (PrivateAccountException e) {
				// 지금은 도달 불가다 — 비공개 판정은 프로필 응답에만 있고 그건 계정 갈래에서 걸린다.
				// 그래도 계정 갈래와 대칭으로 둔다: 단건 경로(fetchPost)에 비공개 판정이 생기는 순간
				// 이 갈래가 없으면 "일반 실패"로 조용히 새어 만료까지 매일 재시도하게 된다.
				log.info("추적 게시물 비공개 — 캠페인 {} 종결: {}", t.id(), t.trackedShortCode());
				closeFailed(t.id(), PRIVATE_ACCOUNT);
			} catch (RuntimeException e) {
				log.warn("캠페인 스윕 실패(격리) — target {}: {}", t.id(), e.toString());
			}
		}
	}

	/** 결정적 수집 불가 — 그 계정의 활성 캠페인을 한꺼번에 종결한다. */
	private void closeAll(String username, List<TargetRow> accountTargets, String failReason) {
		log.info("계정 수집 불가({}) — {} 캠페인 {}건 종결", failReason, username, accountTargets.size());
		accountTargets.forEach(t -> closeFailed(t.id(), failReason));
	}

	/**
	 * 종결도 실패할 수 있다(DB 순단·락 타임아웃). 여기서 예외가 새면 남은 계정이 통째로 안 돌아
	 * "캠페인 하나 종결 실패"가 "그날 스윕 전면 중단"으로 번진다 — 로그만 남기고 계속한다.
	 */
	private void closeFailed(long targetId, String failReason) {
		try {
			targets.close(targetId, TargetStatus.FAILED, failReason);
		} catch (RuntimeException e) {
			log.warn("종결 실패(격리) — target {} → FAILED/{}: {}", targetId, failReason, e.toString());
		}
	}

	private void sweepTarget(TargetRow t, List<PostInfo> posts, Set<String> enumerated) {
		if (t.status() == TargetStatus.WATCHING && t.keywordRule() != null) {
			for (PostInfo p : posts) {
				// matchedTerms가 비어 있으면 미매칭이다 — matches()+matchedTerms() 이중 계산을 피한다.
				List<String> matched = t.keywordRule().matchedTerms(p.caption());
				if (postedAfterRegistration(p, t) && !matched.isEmpty()) {
					candidates.insertPending(t.id(), p.shortCode(), excerpt(p.caption()), matched);
				}
			}
		}
		String tracked = t.status() == TargetStatus.TRACKING ? t.trackedShortCode() : null;
		// 열거 안에 있으면 이미 방금 스냅샷을 남겼다 — 단건을 또 부르면 콜이 두 배가 된다.
		if (tracked != null && !enumerated.contains(tracked)) {
			collect.collectPost(tracked);
		}
		targets.touchFetched(t.id());
	}

	/**
	 * 감지 하한선 — 캠페인 등록 시각 이후에 게시된 것만 후보로 본다(설계 §5, 07-29 확정).
	 * 없으면 첫 스윕에서 등록 전의 옛 키워드 게시물이 통째로 후보로 떠 검토 화면이 노이즈로 찬다.
	 * taken_at을 못 얻은 게시물은 보수적으로 제외한다 — 잘못 올린 후보는 사람이 지워야 하지만,
	 * 빠뜨린 게시물은 다음 스윕에서 taken_at이 채워지면 다시 걸린다(후보 생성은 멱등).
	 */
	private static boolean postedAfterRegistration(PostInfo p, TargetRow t) {
		return p.takenAt() != null
				&& !Instant.ofEpochSecond(p.takenAt()).isBefore(t.registeredAt());
	}

	/**
	 * 계정 열거 필요 여부 — ACCOUNT 등록분이 하나라도 있으면 판단 근거(팔로워 추이·신규 게시물)가 필요하다.
	 * POST 등록분은 등록한 그 게시물만 보므로 단건 콜로 충분하다.
	 */
	private static boolean needsEnumeration(List<TargetRow> ts) {
		return ts.stream().anyMatch(t -> t.type() == TargetType.ACCOUNT);
	}

	private static String excerpt(String caption) {
		if (caption == null) {
			return null;
		}
		return caption.length() <= EXCERPT_LEN ? caption : caption.substring(0, EXCERPT_LEN) + "…";
	}
}
