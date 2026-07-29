package com.celfit.monitoring.service;

import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.store.TargetRepository;
import com.celfit.monitoring.store.TargetRow;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 캠페인 등록 — 검증 → registration_key 멱등 확인 → 동기 첫 수집 → target INSERT.
 * 수집이 먼저다: 계정·게시물이 실재하지 않으면 target 행을 아예 만들지 않는다(계약 §2-1 SUBJECT_NOT_FOUND).
 */
@Service
public class RegistrationService {

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

	public RegistrationService(CollectService collect, TargetRepository targets) {
		this.collect = collect;
		this.targets = targets;
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
		var collected = collect.collectAccount(cmd.username());
		long id = targets.insert(TargetType.ACCOUNT, cmd.username(), null, cmd.keywordRule(),
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
		PostInfo post = collect.collectPost(cmd.shortCode());
		// short_code는 Hiker 응답을 정본으로 쓴다 — 스냅샷도 응답값으로 적재되므로, 요청값을 그대로
		// 저장하면 둘이 갈릴 때(대소문자·별칭) tracked_short_code 조인이 빗나가 뷰 게시물 구획이 영구 null.
		String shortCode = post.shortCode() != null ? post.shortCode() : cmd.shortCode();
		long id = targets.insert(TargetType.POST, post.username(), shortCode, null,
				TargetStatus.TRACKING, shortCode, cmd.registrationKey(), cmd.expiresAt());
		targets.touchFetched(id);
		var snapshot = new PostSnapshot(new PostSnapshot.Post(post.shortCode(), post.contentType(),
				post.likes(), post.comments(), post.views(), post.saves(), post.shares(), post.reposts()));
		return new Result(id, TargetStatus.TRACKING.name(), snapshot, false);
	}

	private static void validate(RegisterCommand cmd) {
		if (isBlank(cmd.registrationKey())) {
			throw new ValidationException("registrationKey는 필수입니다.");
		}
		if (cmd.type() == null) {
			throw new ValidationException("type은 ACCOUNT 또는 POST여야 합니다.");
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
