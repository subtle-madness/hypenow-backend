package com.celfit.monitoring.service;

import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.SubjectNotFoundException;
import com.celfit.monitoring.store.CandidateRepository;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.store.TargetRow;
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
				entry.getValue().forEach(t -> targets.close(t.id(), TargetStatus.FAILED, NOT_FOUND));
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
				targets.close(t.id(), TargetStatus.FAILED, NOT_FOUND);
			} catch (RuntimeException e) {
				log.warn("캠페인 스윕 실패(격리) — target {}: {}", t.id(), e.toString());
			}
		}
	}

	private void sweepTarget(TargetRow t, List<PostInfo> posts, Set<String> enumerated) {
		if (t.status() == TargetStatus.WATCHING && t.keywordRule() != null) {
			for (PostInfo p : posts) {
				if (t.keywordRule().matches(p.caption())) {
					candidates.insertPending(t.id(), p.shortCode(), excerpt(p.caption()));
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
