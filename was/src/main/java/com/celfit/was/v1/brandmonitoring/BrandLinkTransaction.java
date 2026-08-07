package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.v1.common.V1ApiException;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 브랜드 연결의 DB 트랜잭션 경계 — monitoring 호출(느리고 실패 가능)을 트랜잭션 안에 넣지 않기 위해
 * 서비스에서 분리한 단위다. {@link BrandLinkRepository#instagramAccountNameForUpdate}가 users 행을
 * FOR UPDATE로 잠그므로 <b>반드시 트랜잭션 안에서</b> 불러야 한다(밖이면 잠금이 즉시 풀려 동시 등록
 * 경합 방어가 무력화된다) — 그래서 사전 확인(precheck)도 여기 산다.
 *
 * <p>자기호출(self-invocation)은 프록시를 타지 않아 @Transactional이 무시되므로 서비스와 같은 클래스에
 * 두지 않았다. 사전 확인과 저장이 별도 트랜잭션인 것은 의도된 것이다: 사전 확인은 monitoring 호출
 * 전에 명백한 409를 즉시 돌려주기 위한 빠른 경로일 뿐이고, 판정의 정본은 저장 트랜잭션 안의 재확인이다.
 */
@Component
class BrandLinkTransaction {

	private final BrandLinkRepository linkRepository;

	BrandLinkTransaction(BrandLinkRepository linkRepository) {
		this.linkRepository = linkRepository;
	}

	/** monitoring 호출 전 빠른 409 판정(§5-1 2단계). 통과해도 저장 트랜잭션이 같은 검사를 다시 한다. */
	@Transactional
	void precheck(long userId, String username) {
		assertRegisterable(userId, username);
	}

	/** 저장(§5-1 4단계) — 잠금 재확인 → 계정명 최초 저장 → 활성 연결 생성. */
	@Transactional
	void link(long userId, long brandId, String username) {
		String stored = assertRegisterable(userId, username);
		if (stored == null) {
			linkRepository.saveInstagramAccountName(userId, username);
		}
		try {
			linkRepository.insertLink(userId, brandId, username);
		} catch (DuplicateKeyException e) {
			// 활성 유니크 인덱스가 잡은 동시 등록 — 사전 확인·잠금 재확인을 모두 통과한 경합의 최후 보루.
			throw alreadyExists();
		}
	}

	/**
	 * 삭제(§5-3) — 소유권 확인 후 soft-delete하고, 같은 브랜드에 남은 활성 연결 수를 같은 트랜잭션에서 센다.
	 * 활성 연결이 없으면 남의 브랜드인지 이미 해제한 내 브랜드인지 구분할 수 없어 둘 다 403이다.
	 */
	@Transactional
	UnlinkResult unlink(long userId, long brandId) {
		BrandLinkRow link = linkRepository.findActiveByUserAndBrand(userId, brandId)
				.orElseThrow(() -> V1ApiException.forbidden("FORBIDDEN", "브랜드 계정을 찾을 수 없거나 접근 권한이 없어요."));
		linkRepository.softDeleteActiveLink(userId);
		return new UnlinkResult(link.brandId(), link.username(), linkRepository.countActiveByBrand(brandId) == 0);
	}

	/** 회원 탈퇴 훅 — brandId를 모른 채 유저의 활성 연결을 해제한다. 연결이 없으면 empty(할 일 없음). */
	@Transactional
	Optional<UnlinkResult> unlinkForWithdrawal(long userId) {
		Optional<BrandLinkRow> link = linkRepository.findActiveByUser(userId);
		if (link.isEmpty()) {
			return Optional.empty();
		}
		linkRepository.softDeleteActiveLink(userId);
		long brandId = link.get().brandId();
		return Optional.of(new UnlinkResult(brandId, link.get().username(),
				linkRepository.countActiveByBrand(brandId) == 0));
	}

	/**
	 * 등록 가능 여부 판정 — 통과하면 저장된 계정명(null이면 미저장)을 돌려준다.
	 * 순서가 중요하다: 계정명 불변 위반(IMMUTABLE)이 활성 연결 중복(ALREADY_EXISTS)보다 먼저다.
	 * 다른 계정으로 이미 저장된 유저는 활성 연결 유무와 무관하게 항상 IMMUTABLE이어야 한다(§5-4 우회 차단).
	 */
	private String assertRegisterable(long userId, String username) {
		String stored = linkRepository.instagramAccountNameForUpdate(userId);
		if (stored != null && !stored.equals(username)) {
			throw V1ApiException.conflict("BRAND_ACCOUNT_IMMUTABLE", "이미 등록한 브랜드 계정은 변경할 수 없습니다.");
		}
		if (linkRepository.findActiveByUser(userId).isPresent()) {
			throw alreadyExists();
		}
		return stored;
	}

	private static V1ApiException alreadyExists() {
		return V1ApiException.conflict("BRAND_ACCOUNT_ALREADY_EXISTS", "이미 등록된 브랜드 계정입니다.");
	}

	/** 해제 결과 — lastLink=true면 이 브랜드의 마지막 활성 연결이었다(monitoring 탈퇴 대상, §5-3). */
	record UnlinkResult(long brandId, String username, boolean lastLink) {
	}
}
