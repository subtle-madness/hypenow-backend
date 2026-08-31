package com.celfit.monitoring.service;

import com.celfit.instagram.source.PostInfo;
import com.celfit.instagram.source.PrivateAccountException;
import com.celfit.instagram.source.SubjectNotFoundException;
import com.celfit.monitoring.alarm.AlarmRecorder;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.hiker.TargetCallContext;
import com.celfit.monitoring.image.PostThumbnailArchiveJob;
import com.celfit.monitoring.image.ProfileImageArchiveJob;
import com.celfit.monitoring.store.ExpiredTarget;
import com.celfit.monitoring.store.FailingTarget;
import com.celfit.monitoring.store.SnapshotRepository;
import com.celfit.monitoring.store.SweepRunRepository;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.store.TargetRow;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 일일 스윕(KST 02:00) — 만료 처리 → 계정별 1회 수집(캠페인 수와 무관) →
 * WATCHING 키워드 감지 시 즉시 추적 전환 → TRACKING 게시물 보강 → 추적 게시물 댓글 수집.
 * 실패는 계정·캠페인·게시물 단위로 격리한다.
 *
 * <p>트랜잭션을 걸지 않는다: 스윕 한 번은 계정 수만큼 외부 콜을 돌기 때문에
 * 전체를 한 트랜잭션으로 묶으면 커넥션을 몇 분씩 잡고, 마지막 계정의 실패가 앞선 전 계정의
 * 수집을 되돌린다. 커밋 단위는 수집 1회({@link SnapshotWriter})와 상태 전이 1건이다.
 * 구 approve의 "트랜잭션 안에서 외부 콜"은 승계하지 않는다(스펙 §2-2).
 *
 * <p><b>v2.2 상태 머신 개정</b>: 결정적 수집 불가(SUBJECT_NOT_FOUND·PRIVATE_ACCOUNT)를 만나도
 * 더는 target을 FAILED로 종결하지 않는다 — status는 유지한 채 tracked_hidden_at을 세팅해
 * 다음 스윕에서 재공개 여부를 계속 확인한다(계약 §3 status 의미 개정). 재시도 라운드를 다 써도
 * 해소되지 않은 일시 오류는 target 단위로 fetch_failing을 세팅한다(계정 단위가 아니다 — 같은
 * 계정의 성공한 캠페인을 오염시키면 안 된다). 두 신호 모두 복귀 지점은 하나,
 * {@link com.celfit.monitoring.store.TargetRepository#touchFetched}(수집 성공)뿐이다.
 */
@Service
public class DailySweepJob {

	private static final Logger log = LoggerFactory.getLogger(DailySweepJob.class);
	private static final String NOT_FOUND = "SUBJECT_NOT_FOUND";
	private static final String PRIVATE_ACCOUNT = "PRIVATE_ACCOUNT";
	private static final String FETCH_FAILED = "FETCH_FAILED";

	private final TargetRepository targets;
	private final CollectService collect;
	private final AlarmRecorder alarms;
	private final SweepRunRepository sweepRuns;
	private final SnapshotRepository snapshots;
	private final TargetCallContext callContext;
	private final int retryRounds;
	private final Duration retryInterval;
	private final ProfileImageArchiveJob imageArchive;
	private final PostThumbnailArchiveJob thumbnailArchive;

	public DailySweepJob(TargetRepository targets, CollectService collect, AlarmRecorder alarms,
			SweepRunRepository sweepRuns, SnapshotRepository snapshots, TargetCallContext callContext,
			@Value("${monitoring.sweep.retry-rounds:3}") int retryRounds,
			@Value("${monitoring.sweep.retry-interval:10m}") Duration retryInterval,
			ProfileImageArchiveJob imageArchive, PostThumbnailArchiveJob thumbnailArchive) {
		this.targets = targets;
		this.collect = collect;
		this.alarms = alarms;
		this.sweepRuns = sweepRuns;
		this.snapshots = snapshots;
		this.callContext = callContext;
		this.retryRounds = retryRounds;
		this.retryInterval = retryInterval;
		this.imageArchive = imageArchive;
		this.thumbnailArchive = thumbnailArchive;
	}

	/**
	 * sweep_run 기록으로 감싼 진입점 — was가 §3 sweep_run으로 마지막 성공 배치 완료 시각을 읽는다.
	 * 기록 자체의 실패는 스윕을 막지 않는다(start/complete 각각 격리, {@code null} id는 complete 스킵).
	 * 정상 완주(계정 단위 격리 실패 포함)면 ok=true, 예외 이탈(재시도 대기 인터럽트 등)이면 ok=false로
	 * 남기고 예외를 그대로 재전파한다 — try/finally라 별도 catch 없이도 이 순서가 보장된다.
	 */
	public void run() {
		runWithId(startSweepRun());
	}

	/**
	 * 이미 발급된 sweep_run id로 스윕 1회를 실행 — {@link #run()}의 try/finally 격리 계약(정상
	 * 완주 ok=true, 예외 이탈 ok=false 후 재전파)과 완전히 동일하다. 시작 기록만 호출부가 먼저 얻어둔
	 * 경우를 위해 분리했다: 수동 트리거(SweepCommandService)가 202 응답 본문에 runId를 실으려면
	 * sweep_run INSERT가 HTTP 응답 전에 동기로 끝나야 하는데, {@link #run()}은 그 값을 밖으로
	 * 내주지 않기 때문이다. protected인 이유는 테스트(SweepControllerTest)가 스윕 실행 타이밍
	 * (동시 실행 가드 검증)을 통제하려고 오버라이드하기 때문 — 웹 계층 테스트라 다른 패키지에서 상속한다.
	 *
	 * <p>프로필 이미지 아카이브({@link ProfileImageArchiveJob})·게시물 썸네일 아카이브({@link
	 * PostThumbnailArchiveJob}, 트랙 KK 확장)는 finally 안에서, sweep_run 완료 기록 직후 마지막
	 * 단계로 돈다(설계 스펙 §3-1) — 별도 크론이 아니라 스윕이 갓 갱신한 신선한 URL을 바로 잡기
	 * 위함이다. finally라 스윕이 예외로 이탈해도(ok=false) 반드시 실행되고, 반대로 두 아카이브 잡의
	 * 실패는 각각 격리 래퍼가 전부 삼켜 이 finally 밖으로 새지 않으므로 이미 기록된 sweep_run.ok를
	 * 오염시키지 않는다(두 잡도 서로 독립 — 하나가 실패해도 다른 하나는 그대로 실행된다).
	 */
	protected void runWithId(Long runId) {
		boolean ok = false;
		try {
			runSweep();
			ok = true;
		} finally {
			completeSweepRun(runId, ok);
			runProfileImageArchiveSafely();
			runPostThumbnailArchiveSafely();
		}
	}

	/** 건 단위가 아니라 잡 전체를 격리한다 — 스윕 결과(sweep_run)와 무관한 부수 작업이라 예외를 밖으로 내지 않는다. */
	private void runProfileImageArchiveSafely() {
		try {
			imageArchive.run();
		} catch (RuntimeException e) {
			log.warn("프로필 이미지 아카이브 잡 실행 실패(격리) — 스윕 결과에는 영향 없음: {}", e.toString());
		}
	}

	/** runProfileImageArchiveSafely와 동형(트랙 KK 확장) — 두 아카이브 잡은 서로 독립적으로 격리된다. */
	private void runPostThumbnailArchiveSafely() {
		try {
			thumbnailArchive.run();
		} catch (RuntimeException e) {
			log.warn("게시물 썸네일 아카이브 잡 실행 실패(격리) — 스윕 결과에는 영향 없음: {}", e.toString());
		}
	}

	/** package-private — SweepCommandService가 수동 트리거 응답에 실을 runId를 동기로 미리 받아간다. */
	Long startSweepRun() {
		try {
			return sweepRuns.start();
		} catch (RuntimeException e) {
			log.warn("sweep_run 시작 기록 실패(격리) — 스윕은 계속 진행: {}", e.toString());
			return null;
		}
	}

	private void completeSweepRun(Long runId, boolean ok) {
		if (runId == null) {
			return;   // start가 실패했으면 완료 기록도 대상이 없다.
		}
		try {
			sweepRuns.complete(runId, ok);
		} catch (RuntimeException e) {
			log.warn("sweep_run 완료 기록 실패(격리) — run {} ok={}: {}", runId, ok, e.toString());
		}
	}

	private void runSweep() {
		// 만료를 먼저 닫아야 만기 지난 캠페인이 그날 스윕 대상에서 빠진다 — 순서가 바뀌면 종료된 캠페인만큼 콜이 샌다.
		List<ExpiredTarget> expired = targets.expireOverdue();
		// AlarmRecorder가 격리 정책을 스스로 갖는다(record()는 예외를 던지지 않는다) — 여기서 또
		// try/catch로 감싸면 같은 격리를 두 곳에서 반복하는 것이라 격리 코드를 없앤다.
		for (ExpiredTarget e : expired) {
			alarms.collectionEnded(e.id(), e.userId(), e.username(), e.trackedShortCode());
		}
		SweepRoundResult first = sweepRound(null);
		int totalAccounts = first.accountCount();   // 전체 계정 수 — 첫 라운드(only=null)가 활성 계정 전부를 훑은 결과
		int initialFailures = first.transientFailures().size();
		Map<String, Set<Long>> pending = first.transientFailures();
		for (int round = 1; round <= retryRounds && !pending.isEmpty(); round++) {
			// 간격 × 라운드 — 상대가 회복할 시간을 회차마다 늘려 준다.
			sleep(retryInterval.multipliedBy(round));
			log.info("일시 실패 재시도 라운드 {}/{} — 계정 {}건", round, retryRounds, pending.size());
			// 라운드가 pending 계정 전부를 재시도하므로, 결과 맵을 통째로 교체하는 것이 곧 최신 상태다.
			pending = sweepRound(pending.keySet()).transientFailures();
		}
		// 라운드를 다 써도 해소되지 않은 target만 fetch_failing을 세팅한다 — 이미 failing이던 target은
		// markFetchFailing이 갱신하지 않으므로, 반환되는 건 방금 전이된(=알람을 발화해야 하는) 것뿐이다.
		Set<Long> stillFailing = pending.values().stream()
				.flatMap(Set::stream).collect(Collectors.toCollection(LinkedHashSet::new));
		List<FailingTarget> transitionedFailing = targets.markFetchFailing(stillFailing);
		for (FailingTarget f : transitionedFailing) {
			alarms.contentUnavailable(f.id(), f.userId(), f.username(), f.trackedShortCode(), FETCH_FAILED);
		}
		// 계정·게시물 스냅샷과 독립된 갈래 — 댓글 콜 실패가 지표 수집에 번지면 안 된다.
		// 재시도 라운드가 전부 끝난 뒤 상태를 다시 읽는다(hidden 전이는 status를 안 바꾸므로 findActive에 그대로 남는다).
		sweepComments(targets.findActive());
		log.info("스윕 완료 — 계정 {}건 중 미해소 일시 실패 {}건(최초 {}건), 만료 {}건, fetch_failing 전이 {}건",
				totalAccounts, pending.size(), initialFailures, expired.size(), transitionedFailing.size());
	}

	/** 한 라운드의 결과 — 전체 계정 수(완료 로그의 분모)와, 재시도 여지가 있는 계정별 실패 target id. */
	private record SweepRoundResult(int accountCount, Map<String, Set<Long>> transientFailures) {}

	/**
	 * 한 바퀴. {@code only}가 null이면 전체, 아니면 그 계정들만 돈다.
	 * 활성 target을 매 라운드 다시 읽는다 — 앞 라운드에서 전환된 행을 그대로 들고 돌면
	 * 이미 TRACKING인 캠페인을 WATCHING으로 착각해 감지를 두 번 한다.
	 *
	 * @return 이 라운드가 훑은 계정 수와, 결정적 실패(404·비공개)는 이미 hidden 전이됐으므로 빠진
	 * 일시 실패 target id(계정 키 유지 — 재시도 라운드는 username 단위로 재수행한다).
	 */
	private SweepRoundResult sweepRound(Set<String> only) {
		Map<String, List<TargetRow>> byUsername = targets.findActive().stream()
				.filter(t -> only == null || only.contains(t.username()))
				.collect(Collectors.groupingBy(TargetRow::username));
		Map<String, Set<Long>> transientFailures = new LinkedHashMap<>();
		for (var entry : byUsername.entrySet()) {
			try {
				// 계정 갈래(sweepAccount)가 예외 없이 반환해도 그 안의 캠페인 일부가 단건 수집에서
				// 일시 실패했을 수 있다 — 그 target들만 재시도 라운드에 편입한다(사용자 요구:
				// "일시 오류는 당일 안에 무조건 수집" — 열거 밖 추적 게시물 단건 콜도 예외 없다).
				Set<Long> failedIds = sweepAccount(entry.getKey(), entry.getValue());
				if (!failedIds.isEmpty()) {
					transientFailures.put(entry.getKey(), failedIds);
				}
			} catch (SubjectNotFoundException e) {
				// 계정 자체가 없어졌다(삭제·개명) — 재시도해도 결과가 같으니 그 계정의 캠페인을 전부 hidden 전이한다.
				closeAll(entry.getKey(), entry.getValue(), NOT_FOUND);
			} catch (PrivateAccountException e) {
				// 비공개 전환도 결정적 수집 불가다 — status는 유지, tracked_hidden_at만 세팅한다(v2.2, 계약 §3).
				// 일반 실패로 두면 만료일까지 매일 1콜을 태우면서 영원히 WATCHING으로 남는다.
				closeAll(entry.getKey(), entry.getValue(), PRIVATE_ACCOUNT);
			} catch (RuntimeException e) {
				// 재시도 여지가 있는 실패(5xx·타임아웃·셰이프 이상) — 계정 전체가 열거/프로필 단계에서
				// 실패했으므로 그 계정의 target 전부를 이 라운드의 실패로 편입한다.
				log.warn("스윕 실패(격리) — 계정 {}: {}", entry.getKey(), e.toString());
				transientFailures.put(entry.getKey(),
						entry.getValue().stream().map(TargetRow::id)
								.collect(Collectors.toCollection(LinkedHashSet::new)));
			}
		}
		return new SweepRoundResult(byUsername.size(), transientFailures);
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
	 * 추적 게시물 댓글 수집 — 게시물(short_code) 단위로 캠페인 간 공유한다(계약 §3 post_comment).
	 * 같은 게시물을 여러 캠페인이 추적해도 콜은 1회, 실패는 그 게시물만 격리한다.
	 */
	private void sweepComments(List<TargetRow> active) {
		Map<String, String> ownerByShortCode = new LinkedHashMap<>();
		// 콜 집계 스코프 — 게시물 콜 1회가 그 게시물을 추적하는 캠페인 유저 전원을 서빙한다.
		Map<String, Set<Long>> usersByShortCode = new LinkedHashMap<>();
		for (TargetRow t : active) {
			if (t.status() == TargetStatus.TRACKING && t.trackedShortCode() != null) {
				ownerByShortCode.putIfAbsent(t.trackedShortCode(), t.username());
				if (t.userId() != null) {
					usersByShortCode.computeIfAbsent(t.trackedShortCode(), k -> new LinkedHashSet<>())
							.add(t.userId());
				}
			}
		}
		for (var entry : ownerByShortCode.entrySet()) {
			try {
				callContext.runScoped(usersByShortCode.getOrDefault(entry.getKey(), Set.of()),
						() -> collect.collectComments(entry.getKey(), entry.getValue()));
			} catch (RuntimeException e) {
				log.warn("댓글 수집 실패(격리) — 게시물 {}: {}", entry.getKey(), e.toString());
			}
		}
	}

	/**
	 * 계정 1개분 — 열거는 캠페인 수와 무관하게 한 번만 하고, 그 결과를 캠페인들이 나눠 본다.
	 * 여기서 던지는 예외는 계정 전체의 실패다(호출자가 hidden 전이 판단).
	 *
	 * @return 이 계정의 캠페인 중 일시 실패(단건 수집 등)가 있었던 target id들(빈 집합이면 전부 성공).
	 * fetch_failing은 계정 단위가 아니라 target 단위로 추적한다 — 같은 계정에서 성공한 캠페인이
	 * 실패한 캠페인 때문에 오염되면 안 된다. 호출자는 이 id들을 계정 키(username)로 재시도 라운드에
	 * 편입한다 — "일시 오류는 당일 안에 무조건 수집"이 열거 게시물뿐 아니라 열거 밖 추적 게시물 단건
	 * 콜에도 적용돼야 하기 때문이다.
	 */
	private Set<Long> sweepAccount(String username, List<TargetRow> accountTargets) {
		// 콜 집계 스코프(비용 계상) — 계정 공유 콜(열거·프로필·지표 재시도)은 이 계정에 캠페인을 건
		// 유저 전원 몫, 타깃 단건 콜은 그 타깃 소유 유저 몫이다(아래 루프에서 좁혀 재스코프).
		Set<Long> accountUsers = usersOf(accountTargets);
		// POST 등록분만 있는 계정은 열거할 이유가 없다 — 프로필·열거 2~3콜이 통째로 낭비된다.
		boolean needsEnumeration = needsEnumeration(accountTargets);
		CollectService.AccountCollectResult enumResult = needsEnumeration
				? callContext.scoped(accountUsers, () -> collect.collectAccount(username))
				: null;
		List<PostInfo> posts = enumResult != null ? enumResult.posts() : List.of();
		if (!needsEnumeration) {
			callContext.runScoped(accountUsers, () -> collectProfileOnlyOnce(username));
		}
		Set<String> enumerated = posts.stream().map(PostInfo::shortCode).collect(Collectors.toSet());
		Set<Long> transientFailureIds = new LinkedHashSet<>();
		// 저장·리포스트 미관측 추적 릴스 — 캠페인:게시물이 겹칠 수 있어 short_code로 중복 제거한다.
		Map<String, PostInfo> metricsPending = new LinkedHashMap<>();
		for (TargetRow t : accountTargets) {
			try {
				PostInfo trackedPost = callContext.scoped(usersOf(List.of(t)),
						() -> sweepTarget(t, posts, enumerated));
				// 판정은 CollectService와 단일 기준 공유 — 3지표 공통(옵션 ③), 공유 숨김 게시물 제외.
				if (trackedPost != null && CollectService.needsMetricsRetry(trackedPost)) {
					metricsPending.putIfAbsent(trackedPost.shortCode(), trackedPost);
				}
			} catch (SubjectNotFoundException e) {
				// 추적 게시물만 삭제된 경우 — 계정은 멀쩡하니 이 캠페인 하나만 hidden 전이한다.
				log.info("추적 게시물 부재 — 캠페인 {} hidden 전이: {}", t.id(), t.trackedShortCode());
				transitionHidden(t, NOT_FOUND);
			} catch (PrivateAccountException e) {
				// 지금은 도달 불가다 — 비공개 판정은 프로필 응답에만 있고 그건 계정 갈래에서 걸린다.
				// 그래도 계정 갈래와 대칭으로 둔다: 단건 경로(fetchPost)에 비공개 판정이 생기는 순간
				// 이 갈래가 없으면 "일반 실패"로 조용히 새어 만료까지 매일 재시도하게 된다.
				log.info("추적 게시물 비공개 — 캠페인 {} hidden 전이: {}", t.id(), t.trackedShortCode());
				transitionHidden(t, PRIVATE_ACCOUNT);
			} catch (RuntimeException e) {
				// 재시도 여지가 있는 실패(단건 수집 5xx·타임아웃 등) — 상태는 건드리지 않고
				// 이 target만 재시도 라운드에 편입시킨다(스냅샷 upsert가 멱등이라 계정 재스윕은 안전).
				log.warn("캠페인 스윕 실패(격리) — target {}: {}", t.id(), e.toString());
				transientFailureIds.add(t.id());
			}
		}
		callContext.runScoped(accountUsers, () -> retryMetricsSafely(username, enumResult, metricsPending));
		return transientFailureIds;
	}

	/** 콜 집계 귀속 유저 집합 — user_id null(V3 이전 등록 행)은 귀속 불가라 제외한다. */
	private static Set<Long> usersOf(Collection<TargetRow> targetRows) {
		return targetRows.stream().map(TargetRow::userId).filter(Objects::nonNull)
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	/**
	 * 저장·리포스트 세션 복권 재시도(08-04 결정) — 미관측 추적 릴스가 남았으면 clips 열거를 당첨까지
	 * 재콜한다(규칙·상한은 {@link CollectService#retryReelsMetrics}). user_id는 열거 계정이면 방금
	 * 프로필에서, POST 등록만 있는 계정이면 단건 응답의 user.pk(ownerUserId)에서 얻는다 —
	 * 후자가 없어도(구형 셰이프) 건너뛰지 않는다: retryReelsMetrics가 null user_id를 받으면
	 * clips 없이 단건 콜 복권으로만 보강한다(08-05, 창 밖 전환과 같은 경로).
	 *
	 * <p><b>반드시 best-effort여야 한다</b>: 스냅샷은 이미 단건 정본으로 저장을 마쳤다. 여기서 예외가
	 * 새면 성공한 수집이 "일시 실패"로 오분류돼 재시도 라운드가 계정 전체를 다시 돈다.
	 */
	private void retryMetricsSafely(String username, CollectService.AccountCollectResult enumResult,
			Map<String, PostInfo> metricsPending) {
		if (metricsPending.isEmpty()) {
			return;
		}
		String userId = enumResult != null
				? enumResult.profile().userId()
				: metricsPending.values().iterator().next().ownerUserId();
		try {
			collect.retryReelsMetrics(userId, List.copyOf(metricsPending.values()));
		} catch (RuntimeException e) {
			log.warn("저장·리포스트 재시도 실패(격리, best-effort) — 계정 {}: {}", username, e.toString());
		}
	}

	/**
	 * 팔로워 1회 수집(사용자 결정, 트랙 II 후속) — POST 등록분만 있는 계정에 profile_snapshot 행이
	 * 아직 없을 때만 프로필을 1콜 조회해 채운다. 이미 채워졌으면(재공개 포함, 한 번이라도 성공한
	 * 적이 있으면) 다시 부르지 않는다 — 매일 갱신이 목적이 아니라 was가 서빙하는 followers가
	 * 최신 1행 단일값(시계열 아님)이라 최초 수집 이후 갱신 실익이 없기 때문이다.
	 *
	 * <p><b>반드시 best-effort여야 한다.</b> 팔로워 수는 부가 표시 정보다. 여기서 예외가 새면
	 * {@link #sweepRound}의 catch가 그 계정의 캠페인을 통째로 hidden 전이시킨다({@link #closeAll}) —
	 * 추적 게시물은 멀쩡한데 프로필 조회 실패만으로 캠페인이 죽는 새 고장 경로가 생긴다. POST
	 * 등록분의 생존 판정은 지금까지처럼 단건 게시물 수집 성공 여부 하나로만 유지해야 하므로,
	 * {@link com.celfit.instagram.source.PrivateAccountException}·
	 * {@link com.celfit.instagram.source.SubjectNotFoundException}을 포함한 모든 예외를 여기서 삼킨다
	 * (기존 판정 경로를 바꾸지 않는다는 뜻 — 이 계정이 실제로 없어졌는지 비공개인지는 단건 게시물
	 * 콜이 이미 스윕 나머지 갈래에서 판정한다).
	 */
	private void collectProfileOnlyOnce(String username) {
		try {
			if (!snapshots.hasProfileSnapshot(username)) {
				collect.collectProfileOnly(username);
			}
		} catch (RuntimeException e) {
			log.warn("팔로워 1회 수집 실패(격리, best-effort) — 계정 {}: {}", username, e.toString());
		}
	}

	/** 결정적 수집 불가 — 그 계정의 활성 캠페인을 한꺼번에 hidden 전이한다(status는 유지). */
	private void closeAll(String username, List<TargetRow> accountTargets, String failReason) {
		log.info("계정 수집 불가({}) — {} 캠페인 {}건 hidden 전이", failReason, username, accountTargets.size());
		accountTargets.forEach(t -> transitionHidden(t, failReason));
	}

	/**
	 * 결정적 수집 불가 신호 — status는 유지한 채 tracked_hidden_at만 세팅한다(v2.2, 계약 §3 status
	 * 의미 개정). status를 안 바꾸므로 findActive가 이 target을 계속 대상에 포함해 다음 스윕에서
	 * 재공개 여부를 확인한다 — 복귀는 {@link TargetRepository#touchFetched}(수집 성공) 하나뿐이다.
	 *
	 * <p>markHidden은 이미 hidden이면 false를 돌려준다(전이 없음) — 그때는 알람을 재발화하지 않는다
	 * (계약 §3 alarm_event "전이 시점에만 1회"). markHidden 자체가 실패할 수도 있다(DB 순단·락
	 * 타임아웃) — 여기서 예외가 새면 남은 계정이 통째로 안 돌아 "캠페인 하나 hidden 전이 실패"가
	 * "그날 스윕 전면 중단"으로 번진다 — 로그만 남기고 계속한다(기존 closeFailed의 격리 관례 유지).
	 */
	private void transitionHidden(TargetRow t, String failReason) {
		boolean transitioned;
		try {
			transitioned = targets.markHidden(t.id());
		} catch (RuntimeException e) {
			log.warn("hidden 전이 실패(격리) — target {} → {}: {}", t.id(), failReason, e.toString());
			return;
		}
		if (transitioned) {
			alarms.contentUnavailable(t.id(), t.userId(), t.username(), t.trackedShortCode(), failReason);
		}
	}

	/** @return 이번 스윕에서 단건 정본으로 수집한 추적 게시물(저장·리포스트 재시도 판정용) — 없으면 null. */
	private PostInfo sweepTarget(TargetRow t, List<PostInfo> posts, Set<String> enumerated) {
		if (t.status() == TargetStatus.WATCHING && t.keywordRule() != null) {
			PostInfo detected = firstDetection(t, posts);
			if (detected != null) {
				// 승인 단계 없이 바로 추적으로 넘어간다(스펙 §2-2).
				targets.markTracking(t.id(), detected.shortCode(), t.keywordRule().matchedTerms(detected.caption()));
				alarms.collectionStarted(t.id(), t.userId(), t.username(), detected.shortCode());
				log.info("첫 감지 자동 전환 — target {} → TRACKING {}", t.id(), detected.shortCode());
				// 감지 당일도 추적 게시물 규칙(아래)과 동일하게 단건 정본 수집 — 열거 스냅샷만 남기면
				// 캠페인 첫날부터 공유수가 세션 복불복에 걸린다(1번 결정, 08-04).
				PostInfo collected = collect.collectTrackedPost(detected.shortCode(), detected);
				targets.touchFetched(t.id());
				return collected;
			}
		}
		String tracked = t.status() == TargetStatus.TRACKING ? t.trackedShortCode() : null;
		PostInfo collected = null;
		if (tracked != null) {
			// 추적 게시물은 열거 포함 여부와 무관하게 단건 1콜이 정본이다(1번 결정, 08-04) — 열거 응답은
			// 세션에 따라 공유·저장·리포스트 키가 빠지지만 단건은 좋아요·댓글·조회·공유를 확정적으로
			// 준다. 열거에 있었으면 그 관측을 폴백으로 머지해 방금 적재한 값의 유실을 막는다.
			collected = collect.collectTrackedPost(tracked, enumeratedPost(posts, tracked, enumerated));
		}
		targets.touchFetched(t.id());
		return collected;
	}

	/** 열거 결과에서 추적 게시물 1건 — 열거 밖(또는 열거 안 탄 계정)이면 null, 머지 폴백 없이 단건만 쓴다. */
	private static PostInfo enumeratedPost(List<PostInfo> posts, String shortCode, Set<String> enumerated) {
		if (!enumerated.contains(shortCode)) {
			return null;
		}
		return posts.stream()
				.filter(p -> shortCode.equals(p.shortCode()))
				.findFirst()
				.orElse(null);
	}

	/**
	 * 첫 감지 1건 — 캠페인:추적 게시물은 1:1이라 같은 스윕에 여러 개가 걸려도 하나만 고른다.
	 * 기준은 게시 시각 최신: 열거 순서에 기대면 핀 고정 게시물(taken_at 2023년 사례 — findings §3)이
	 * 먼저 잡힐 수 있고, HikerClient의 재정렬에 암묵 의존하는 코드가 된다.
	 *
	 * <p><b>같은 유저 이중 추적 배제</b>(계약 §6.25) — 키워드 매칭 후보 중 같은 user_id의 다른 활성
	 * target이 이미 추적 중인 shortcode는 후보에서 뺀다. 유저 스코프를 빼고 전역으로 구현하면 다른
	 * 유저가 먼저 추적 중인 게시물이 이 유저의 감지에서 조용히 빠진다 — 브랜드와 대행사가 같은
	 * 인플루언서를 각자 시딩하는 건 정상 시나리오라 이걸 막으면 안 된다(§6.25). "활성"은 hidden
	 * (tracked_hidden_at)·error(fetch_failing) 상태도 포함한다(사용자 결정) — 비공개 전환으로 hidden된
	 * 행이 재공개 시 tracking으로 복귀하는데, 그 사이 감지가 새 행을 만들면 이중 추적이 되기 때문이다.
	 * 배제 쿼리는 매칭 후보가 1건 이상일 때만 호출한다 — 평상시(매칭 없음) 스윕에 DB 콜을 더하지 않는다.
	 *
	 * <p>후보가 배제로 전부 사라지면 이번 스윕은 전환하지 않고 WATCHING을 유지한다 — 다음 스윕에서
	 * 자연 재시도된다(같은 유저의 다른 target이 그사이 종결되면 그때 잡힌다).
	 *
	 * <p>알려진 한계: was의 pending 행(등록 접수됐으나 monitoring target이 아직 없는 상태)은 이 배제가
	 * 볼 수 없다 — monitoring은 시스템 경계상 was DB에 접근하지 않는다. 등록 접수 직후 수 분의 창에서는
	 * 감지가 같은 게시물을 잡을 수 있다(수용된 한계, 계약 §6.25).
	 */
	private PostInfo firstDetection(TargetRow t, List<PostInfo> posts) {
		List<PostInfo> candidates = posts.stream()
				.filter(p -> postedAfterRegistration(p, t) && t.keywordRule().matches(p.caption()))
				.toList();
		if (candidates.isEmpty()) {
			return null;   // 매칭 자체가 없으면 배제 쿼리를 부를 이유가 없다.
		}
		Set<String> excluded = excludedShortCodes(t);
		List<PostInfo> allowed = excluded.isEmpty() ? candidates
				: candidates.stream().filter(p -> !excluded.contains(p.shortCode())).toList();
		if (allowed.isEmpty()) {
			log.info("감지 후보 전부 이중 추적 배제 — target {} 후보 {}건이 같은 유저의 다른 활성 target에서 이미 추적 중",
					t.id(), candidates.size());
			return null;
		}
		return allowed.stream()
				// taken_at 동률이면 short_code 사전순 — API 응답 순서에 기대지 않는 결정론
				.max(Comparator.comparing(PostInfo::takenAt)   // 필터가 takenAt != null을 보장한다
						.thenComparing(PostInfo::shortCode))
				.orElse(null);
	}

	/** user_id가 없는 target(V3 이전 등록분)은 유저 스코프 배제가 불가하다 — 건너뛰고 기존 동작을 유지한다. */
	private Set<String> excludedShortCodes(TargetRow t) {
		if (t.userId() == null) {
			log.warn("user_id 없음 — 이중 추적 배제 스킵(V3 이전 등록분): target {}", t.id());
			return Set.of();
		}
		return targets.findTrackedShortCodesByUser(t.userId(), t.id());
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
