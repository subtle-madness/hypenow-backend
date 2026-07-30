package com.celfit.monitoring.service;

import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.PrivateAccountException;
import com.celfit.monitoring.hiker.SubjectNotFoundException;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.store.TargetRow;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 일일 스윕(KST 02:00) — 만료 처리 → 계정별 1회 수집(캠페인 수와 무관) →
 * WATCHING 키워드 감지 시 즉시 추적 전환 → TRACKING 게시물 보강. 실패는 계정·캠페인 단위로 격리한다.
 *
 * <p>트랜잭션을 걸지 않는다: 스윕 한 번은 계정 수만큼 외부 콜을 돌기 때문에
 * 전체를 한 트랜잭션으로 묶으면 커넥션을 몇 분씩 잡고, 마지막 계정의 실패가 앞선 전 계정의
 * 수집을 되돌린다. 커밋 단위는 수집 1회({@link SnapshotWriter})와 상태 전이 1건이다.
 * 구 approve의 "트랜잭션 안에서 외부 콜"은 승계하지 않는다(스펙 §2-2).
 */
@Service
public class DailySweepJob {

	private static final Logger log = LoggerFactory.getLogger(DailySweepJob.class);
	private static final String NOT_FOUND = "SUBJECT_NOT_FOUND";
	private static final String PRIVATE_ACCOUNT = "PRIVATE_ACCOUNT";

	private final TargetRepository targets;
	private final CollectService collect;
	private final int retryRounds;
	private final Duration retryInterval;

	public DailySweepJob(TargetRepository targets, CollectService collect,
			@Value("${monitoring.sweep.retry-rounds:3}") int retryRounds,
			@Value("${monitoring.sweep.retry-interval:10m}") Duration retryInterval) {
		this.targets = targets;
		this.collect = collect;
		this.retryRounds = retryRounds;
		this.retryInterval = retryInterval;
	}

	public void run() {
		// 만료를 먼저 닫아야 만기 지난 캠페인이 그날 스윕 대상에서 빠진다 — 순서가 바뀌면 종료된 캠페인만큼 콜이 샌다.
		int expired = targets.expireOverdue();
		Set<String> pending = sweepRound(null);
		int accounts = pending.size();   // 라운드 로그용 초기 실패 수(전체 계정 수는 sweepRound가 남긴다)
		for (int round = 1; round <= retryRounds && !pending.isEmpty(); round++) {
			// 간격 × 라운드 — 상대가 회복할 시간을 회차마다 늘려 준다.
			sleep(retryInterval.multipliedBy(round));
			log.info("일시 실패 재시도 라운드 {}/{} — 계정 {}건", round, retryRounds, pending.size());
			pending = sweepRound(pending);
		}
		log.info("스윕 완료 — 만료 {}건, 미해소 일시 실패 {}건(최초 {}건)", expired, pending.size(), accounts);
	}

	/**
	 * 한 바퀴. {@code only}가 null이면 전체, 아니면 그 계정들만 돈다.
	 * 활성 target을 매 라운드 다시 읽는다 — 앞 라운드에서 전환·종결된 행을 그대로 들고 돌면
	 * 이미 TRACKING인 캠페인을 WATCHING으로 착각해 감지를 두 번 한다.
	 *
	 * @return 재시도 여지가 있는 실패(일시 오류) 계정. 결정적 실패(404·비공개)는 이미 종결됐으므로 빠진다.
	 */
	private Set<String> sweepRound(Set<String> only) {
		Map<String, List<TargetRow>> byUsername = targets.findActive().stream()
				.filter(t -> only == null || only.contains(t.username()))
				.collect(Collectors.groupingBy(TargetRow::username));
		Set<String> transientFailures = new LinkedHashSet<>();
		for (var entry : byUsername.entrySet()) {
			try {
				sweepAccount(entry.getKey(), entry.getValue());
			} catch (SubjectNotFoundException e) {
				// 계정 자체가 없어졌다(삭제·개명) — 재시도해도 결과가 같으니 그 계정의 캠페인을 전부 종결한다.
				closeAll(entry.getKey(), entry.getValue(), NOT_FOUND);
			} catch (PrivateAccountException e) {
				// 비공개 전환도 결정적 수집 불가다(설계 §5 "계정 소멸·비공개 등 → FAILED").
				// 일반 실패로 두면 만료일까지 매일 1콜을 태우면서 영원히 WATCHING으로 남는다.
				closeAll(entry.getKey(), entry.getValue(), PRIVATE_ACCOUNT);
			} catch (RuntimeException e) {
				// 재시도 여지가 있는 실패(5xx·타임아웃·셰이프 이상)는 상태를 건드리지 않고 다음 라운드로 넘긴다.
				log.warn("스윕 실패(격리) — 계정 {}: {}", entry.getKey(), e.toString());
				transientFailures.add(entry.getKey());
			}
		}
		return transientFailures;
	}

	/** 라운드 사이 대기. 인터럽트는 종료 신호라 남은 라운드를 포기한다(다음날 스윕이 회복시킨다). */
	private static void sleep(Duration duration) {
		if (duration.isZero() || duration.isNegative()) {
			return;
		}
		try {
			Thread.sleep(duration.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("스윕 재시도 대기 중단", e);
		}
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
				closeFailed(t, NOT_FOUND);
			} catch (PrivateAccountException e) {
				// 지금은 도달 불가다 — 비공개 판정은 프로필 응답에만 있고 그건 계정 갈래에서 걸린다.
				// 그래도 계정 갈래와 대칭으로 둔다: 단건 경로(fetchPost)에 비공개 판정이 생기는 순간
				// 이 갈래가 없으면 "일반 실패"로 조용히 새어 만료까지 매일 재시도하게 된다.
				log.info("추적 게시물 비공개 — 캠페인 {} 종결: {}", t.id(), t.trackedShortCode());
				closeFailed(t, PRIVATE_ACCOUNT);
			} catch (RuntimeException e) {
				log.warn("캠페인 스윕 실패(격리) — target {}: {}", t.id(), e.toString());
			}
		}
	}

	/** 결정적 수집 불가 — 그 계정의 활성 캠페인을 한꺼번에 종결한다. */
	private void closeAll(String username, List<TargetRow> accountTargets, String failReason) {
		log.info("계정 수집 불가({}) — {} 캠페인 {}건 종결", failReason, username, accountTargets.size());
		accountTargets.forEach(t -> closeFailed(t, failReason));
	}

	/**
	 * 종결도 실패할 수 있다(DB 순단·락 타임아웃). 여기서 예외가 새면 남은 계정이 통째로 안 돌아
	 * "캠페인 하나 종결 실패"가 "그날 스윕 전면 중단"으로 번진다 — 로그만 남기고 계속한다.
	 */
	private void closeFailed(TargetRow t, String failReason) {
		try {
			targets.close(t.id(), TargetStatus.FAILED, failReason);
		} catch (RuntimeException e) {
			log.warn("종결 실패(격리) — target {} → FAILED/{}: {}", t.id(), failReason, e.toString());
		}
	}

	private void sweepTarget(TargetRow t, List<PostInfo> posts, Set<String> enumerated) {
		if (t.status() == TargetStatus.WATCHING && t.keywordRule() != null) {
			PostInfo detected = firstDetection(t, posts);
			if (detected != null) {
				// 승인 단계 없이 바로 추적으로 넘어간다(스펙 §2-2). 지표는 방금 열거에서 이미 적재됐으므로
				// 추가 단건 콜을 쏘지 않는다 — 감지 대상 자체가 열거 결과라 항상 enumerated 안에 있다.
				targets.markTracking(t.id(), detected.shortCode());
				log.info("첫 감지 자동 전환 — target {} → TRACKING {}", t.id(), detected.shortCode());
				targets.touchFetched(t.id());
				return;
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
	 * 첫 감지 1건 — 캠페인:추적 게시물은 1:1이라 같은 스윕에 여러 개가 걸려도 하나만 고른다.
	 * 기준은 게시 시각 최신: 열거 순서에 기대면 핀 고정 게시물(taken_at 2023년 사례 — findings §3)이
	 * 먼저 잡힐 수 있고, HikerClient의 재정렬에 암묵 의존하는 코드가 된다.
	 */
	private static PostInfo firstDetection(TargetRow t, List<PostInfo> posts) {
		return posts.stream()
				.filter(p -> postedAfterRegistration(p, t) && t.keywordRule().matches(p.caption()))
				// taken_at 동률이면 short_code 사전순 — API 응답 순서에 기대지 않는 결정론
				.max(Comparator.comparing(PostInfo::takenAt)   // 필터가 takenAt != null을 보장한다
						.thenComparing(PostInfo::shortCode))
				.orElse(null);
	}

	/**
	 * 감지 하한선 — 캠페인 등록 시각 이후에 게시된 것만 본다(설계 §5, 07-29 확정).
	 * 없으면 첫 스윕에서 등록 전의 옛 키워드 게시물을 추적 대상으로 잡아 캠페인이 통째로 헛돈다.
	 * taken_at을 못 얻은 게시물은 보수적으로 제외한다 — 잘못 잡은 추적은 되돌릴 수 없지만,
	 * 빠뜨린 게시물은 다음 스윕에서 taken_at이 채워지면 다시 걸린다.
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
}
