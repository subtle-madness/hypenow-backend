package com.celfit.monitoring.service;

import com.celfit.monitoring.alarm.AlarmRecorder;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.store.TargetRow;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 캠페인 등록 — 검증 → registration_key 멱등 확인 → 동기 첫 수집 → target INSERT.
 * 수집이 먼저다: 계정·게시물이 실재하지 않으면 target 행을 아예 만들지 않는다(계약 §2-1 SUBJECT_NOT_FOUND).
 */
@Service
public class RegistrationService {

	private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

	/** 등록 결과 — replayed는 HTTP 코드(201/200) 결정용이라 응답 본문에는 싣지 않는다. */
	public record Result(long targetId, String status, Object firstSnapshot, boolean replayed) {}

	/** ACCOUNT 등록의 firstSnapshot — 계약 §2-1. */
	public record AccountSnapshot(Profile profile, int recentPostCount) {
		public record Profile(Long followers, Long following, Long mediaCount) {}
	}

	/** POST 등록의 firstSnapshot — profile 자리에 게시물 6지표가 온다(취득 불가 지표는 null). */
	public record PostSnapshot(Post post) {
		public record Post(String shortCode, String contentType, Long likes, Long comments,
				Long views, Long saves, Long shares, Long reposts) {}
	}

	private final CollectService collect;
	private final TargetRepository targets;
	private final AlarmRecorder alarms;
	private final Executor metricsBackfill;

	/**
	 * metricsBackfill은 등록 직후 저장·리포스트 세션 복권 재시도(08-04)를 동기 응답 밖에서 돌리는
	 * executor다 — 등록은 was 10초 read timeout 예산 안의 동기 경로라 재시도 루프(최대 6회×10s)를
	 * 품을 수 없고, 그렇다고 안 돌리면 등록 당일 스냅샷이 다음날 새벽 스윕까지 빈다.
	 */
	public RegistrationService(CollectService collect, TargetRepository targets, AlarmRecorder alarms,
			@Qualifier("metricsBackfillExecutor") Executor metricsBackfill) {
		this.collect = collect;
		this.targets = targets;
		this.alarms = alarms;
		this.metricsBackfill = metricsBackfill;
	}

	public Result register(RegisterCommand cmd) {
		validate(cmd);
		// 멱등 replay — was의 재시도(타임아웃·크래시 복구)가 중복 캠페인을 만들지 않게 한다.
		var existing = targets.findByRegistrationKey(cmd.registrationKey());
		if (existing.isPresent()) {
			return replay(existing.get());
		}
		try {
			return cmd.type() == TargetType.POST ? registerPost(cmd) : registerAccount(cmd);
		} catch (DuplicateKeyException e) {
			// 같은 키로 동시에 두 요청이 들어온 경우 — 먼저 커밋한 행을 replay로 돌려준다.
			return replay(targets.findByRegistrationKey(cmd.registrationKey()).orElseThrow(() -> e));
		}
	}

	/**
	 * replay 응답에는 firstSnapshot을 싣지 않는다 — 재시도마다 Hiker를 다시 부르면 콜 과금이 배로 는다.
	 * 첫 수집분은 이미 스냅샷 테이블에 있고, was는 조회 표면(§3)에서 SELECT로 본다.
	 */
	private static Result replay(TargetRow row) {
		return new Result(row.id(), row.status().name(), null, true);
	}

	private Result registerAccount(RegisterCommand cmd) {
		var collected = collect.collectAccountForRegistration(cmd.username());
		long id = targets.insert(TargetType.ACCOUNT, cmd.userId(), cmd.username(), null, cmd.keywordRule(),
				TargetStatus.WATCHING, null, cmd.registrationKey(), cmd.expiresAt());
		targets.touchFetched(id);
		var p = collected.profile();
		var snapshot = new AccountSnapshot(
				new AccountSnapshot.Profile(p.followers(), p.following(), p.mediaCount()),
				collected.posts().size());
		return new Result(id, TargetStatus.WATCHING.name(), snapshot, false);
	}

	/**
	 * POST 등록은 감지·승인 단계가 없다 — 등록 즉시 그 게시물을 추적한다(TRACKING).
	 * username(소유 계정)은 사용자가 주지 않고 단건 응답에서 얻는다 — 부재 판정은 HikerClient.fetchPost가
	 * 이미 했다(셰이프 이상 → FETCH_FAILED 502). 여기서 다시 보면 upsert가 먼저 터져서 죽은 가드가 된다.
	 */
	private Result registerPost(RegisterCommand cmd) {
		PostInfo post = collect.collectPostForRegistration(cmd.shortCode());
		// short_code는 Hiker 응답을 정본으로 쓴다 — 스냅샷도 응답값으로 적재되므로, 요청값을 그대로
		// 저장하면 둘이 갈릴 때(대소문자·별칭) tracked_short_code 조인이 빗나가 뷰 게시물 구획이 영구 null.
		// null이 아니라 isBlank로 본다 — HikerClient.toPost의 code는 키 부재 시 빈 문자열이라 null 검사는 죽는다.
		String shortCode = isBlank(post.shortCode()) ? cmd.shortCode() : post.shortCode();
		// 등록 직후 댓글까지 즉시 수집한다 — 그렇지 않으면 DailySweepJob.sweepComments가 도는
		// 다음 스윕까지 최대 24시간 댓글 본문이 비어 있다(ACCOUNT 모드는 첫 감지가 스윕 안에서
		// 일어나 같은 런에서 수집되므로 이 공백이 없다 — POST 모드에서만 발생).
		// best-effort: 실패해도 등록은 성공시킨다 — 당일 스윕이 이미 백스톱이라 손실은
		// "현행 동작(공백 24시간)으로 되돌아감"뿐이다. 게시물 스냅샷이 커밋된 뒤에만 시도한다.
		// 등록은 1페이지만 부른다(collectCommentsForRegistration) — 나머지는 그날 스윕(3페이지)이 채운다.
		try {
			collect.collectCommentsForRegistration(shortCode, post.username());
		} catch (RuntimeException e) {
			log.warn("댓글 수집 실패(격리) — 게시물 {}: {}", shortCode, e.toString());
		}
		long id = targets.insert(TargetType.POST, cmd.userId(), post.username(), shortCode, null,
				TargetStatus.TRACKING, shortCode, cmd.registrationKey(), cmd.expiresAt());
		targets.touchFetched(id);
		// 게시물 직접 등록은 등록 = 수집 시작이다. replay 경로는 여기 오지 않으므로 재시도로 중복되지 않는다.
		// alarms.collectionStartedImmediate는 AlarmRecorder 정책상 예외를 던지지 않는다 — 적재가
		// 실패해도 등록 자체는 계속 201로 성공하고, 그 알람 이벤트는 재시도 없이 유실된다(로그로만 관측).
		// replay는 target 중복 방지(멱등)만 보장할 뿐 이 알람 유실을 복구하지 않는다.
		alarms.collectionStartedImmediate(id, cmd.userId(), post.username(), shortCode);
		scheduleMetricsBackfill(post);
		var snapshot = new PostSnapshot(new PostSnapshot.Post(post.shortCode(), post.contentType(),
				post.likes(), post.comments(), post.views(), post.saves(), post.shares(), post.reposts()));
		return new Result(id, TargetStatus.TRACKING.name(), snapshot, false);
	}

	/**
	 * 등록 직후 저장·리포스트 백필(08-04) — 단건 응답이 꽝 세션(저장·리포스트 키 부재)이면 응답을
	 * 보낸 뒤 백그라운드에서 clips 재시도(규칙·상한은 {@link CollectService#retryReelsMetrics})를 돌려
	 * 등록 당일 스냅샷 공백을 없앤다. 동기 응답의 firstSnapshot은 여전히 null일 수 있다 —
	 * FE 조회 표면은 스냅샷 테이블 SELECT라 백필 완료(최대 ~1분) 후 새로고침이면 채워진다.
	 * best-effort: 실패해도 등록은 이미 성공했고, 다음날 새벽 스윕이 백스톱이다.
	 * ACCOUNT 등록은 대상이 아니다 — 추적 게시물이 아직 없고(WATCHING) 감지는 스윕 안에서 일어난다.
	 * ownerUserId가 없어도(구형 셰이프) 건너뛰지 않는다 — retryReelsMetrics가 null user_id면
	 * clips 없이 단건 콜 복권으로만 보강한다(08-05).
	 */
	private void scheduleMetricsBackfill(PostInfo post) {
		// 공유수도 판정에 포함한다(08-05 옵션 ③) — 부분 세션이 공유만 빠뜨린 등록의 단독 누락 방지.
		boolean needsBackfill = "REELS".equals(post.contentType())
				&& (post.saves() == null || post.shares() == null || post.reposts() == null);
		if (!needsBackfill) {
			return;
		}
		metricsBackfill.execute(() -> {
			try {
				collect.retryReelsMetrics(post.ownerUserId(), List.of(post));
			} catch (RuntimeException e) {
				log.warn("등록 직후 저장·리포스트 백필 실패(격리) — {}: {}", post.shortCode(), e.toString());
			}
		});
	}

	private static void validate(RegisterCommand cmd) {
		if (isBlank(cmd.registrationKey())) {
			throw new ValidationException("registrationKey는 필수입니다.");
		}
		if (cmd.type() == null) {
			throw new ValidationException("type은 ACCOUNT 또는 POST여야 합니다.");
		}
		if (cmd.userId() == null) {
			// 뒤늦게 채울 방법이 없다 — 캠페인이 만들어지고 나면 그 소유자를 monitoring이 알 길이 없다.
			throw new ValidationException("userId는 필수입니다.");
		}
		if (cmd.expiresAt() == null) {
			throw new ValidationException("expiresAt은 필수입니다.");
		}
		if (!cmd.expiresAt().isAfter(Instant.now())) {
			throw new ValidationException("expiresAt은 미래 시각이어야 합니다.");
		}
		if (cmd.type() == TargetType.ACCOUNT) {
			if (isBlank(cmd.username())) {
				throw new ValidationException("ACCOUNT 등록에는 username이 필요합니다.");
			}
			// KeywordRule 생성자가 공백·null 원소를 이미 걸러냈다 — 여기 걸리면 실제로 유효 키워드가 없다.
			if (cmd.keywordRule() == null || !cmd.keywordRule().isValid()) {
				throw new ValidationException("keywordRule의 and·any 중 최소 한 목록은 비어 있지 않아야 합니다.");
			}
		} else if (isBlank(cmd.shortCode())) {
			throw new ValidationException("POST 등록에는 shortCode가 필요합니다.");
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
