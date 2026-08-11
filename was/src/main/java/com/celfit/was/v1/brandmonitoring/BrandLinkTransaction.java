package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.v1.common.V1ApiException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 브랜드 연결의 DB 트랜잭션 경계 — monitoring 호출(느리고 실패 가능)을 트랜잭션 안에 넣지 않기 위해
 * 서비스에서 분리한 단위다. 08-07 다계정 개정: POST는 "계정명 등록(불변)"이 아니라 "브랜드 연결"이다 —
 * 유저는 브랜드를 타입별 한도(own 6 / competitor 3 — {@link BrandAccountType})까지 연결할 수 있고,
 * 이미 연결된 브랜드 재요청은 멱등이다.
 * BRAND_ACCOUNT_IMMUTABLE·BRAND_ACCOUNT_ALREADY_EXISTS는 이 개정으로 폐기됐다.
 *
 * <p>자기호출(self-invocation)은 프록시를 타지 않아 @Transactional이 무시되므로 서비스와 같은 클래스에
 * 두지 않았다. 사전 확인과 저장이 별도 트랜잭션인 것은 의도된 것이다: 사전 확인은 monitoring 호출
 * 전에 멱등·한도를 즉시 판정하는 빠른 경로일 뿐이고, 판정의 정본은 저장 트랜잭션 안의
 * {@code lockUser}(유저 행 FOR UPDATE) 직렬화 아래 재확인이다 — 한도는 유니크 인덱스로 표현할 수
 * 없어 이 잠금이 동시 등록의 한도 초과를 막는 유일한 장치다.
 */
@Component
class BrandLinkTransaction {

	private final BrandLinkRepository linkRepository;

	BrandLinkTransaction(BrandLinkRepository linkRepository) {
		this.linkRepository = linkRepository;
	}

	/**
	 * monitoring 호출 전 빠른 판정(§5-1 2단계) — 같은 계정명이 이미 연결돼 있으면 그 brandId를
	 * 돌려준다(멱등 경로 — monitoring 호출 자체를 생략). 타입이 다르면 <b>그 자리에서 타입만
	 * 바꾼다</b>(08-12): FE UX가 "이미 등록된 계정을 다시 넣으면 경쟁사로 옮겨진다"라 409가 아니다.
	 * 대상 타입 한도 초과는 즉시 409.
	 *
	 * <p>계정명 비교는 링크의 등록 시점 사본 기준이라 monitoring 쪽 개명은 못 보지만, 그 경우
	 * monitoring 등록이 같은 brandId로 replay돼 {@link #link}의 brandId 재확인이 멱등으로 접는다 —
	 * 결과는 같다.
	 */
	@Transactional
	Optional<Long> precheck(long userId, String username, String accountType) {
		List<BrandLinkRow> links = linkRepository.findAllActiveByUser(userId);
		Optional<BrandLinkRow> same = links.stream()
				.filter(link -> link.username().equals(username))
				.findFirst();
		if (same.isPresent()) {
			BrandLinkRow link = same.get();
			if (!accountType.equals(link.accountType())) {
				requireRoom(links, accountType, link.brandId());
				linkRepository.updateAccountType(userId, link.brandId(), accountType);
			}
			return Optional.of(link.brandId());
		}
		requireRoom(links, accountType, null);
		return Optional.empty();
	}

	/**
	 * 저장(§5-1 4단계) — 유저 잠금 → 멱등·한도 재확인 → 활성 연결 생성.
	 * 이미 연결된 브랜드면 타입만 맞추고 성공한다(멱등 — monitoring 등록은 replay라 부작용이 없다).
	 */
	@Transactional
	void link(long userId, long brandId, String username, String accountType) {
		linkRepository.lockUser(userId);
		List<BrandLinkRow> links = linkRepository.findAllActiveByUser(userId);
		Optional<BrandLinkRow> existing = links.stream()
				.filter(link -> link.brandId() == brandId)
				.findFirst();
		if (existing.isPresent()) {
			if (!accountType.equals(existing.get().accountType())) {
				requireRoom(links, accountType, brandId);
				linkRepository.updateAccountType(userId, brandId, accountType);
			}
			return;
		}
		requireRoom(links, accountType, null);
		try {
			linkRepository.insertLink(userId, brandId, username, accountType);
		} catch (DuplicateKeyException e) {
			// (유저, 브랜드) 활성 유니크가 잡은 동시 같은 요청 — 잠금 덕에 사실상 도달 불가지만,
			// 도달해도 원하는 상태(연결됨)는 이미 성립했으므로 멱등 성공으로 접는다.
		}
	}

	/**
	 * 타입 변경(PATCH, 08-12) — 재수집 없이 관계 속성만 바꾼다. 소유권은 활성 연결로 검증하고
	 * (남의 brandId는 403), 대상 타입 상한 초과는 409다. 이미 그 타입이면 조용히 성공(멱등).
	 */
	@Transactional
	void changeType(long userId, long brandId, String accountType) {
		linkRepository.lockUser(userId);
		List<BrandLinkRow> links = linkRepository.findAllActiveByUser(userId);
		BrandLinkRow target = links.stream()
				.filter(link -> link.brandId() == brandId)
				.findFirst()
				.orElseThrow(() -> V1ApiException.forbidden("FORBIDDEN", "브랜드 계정을 찾을 수 없거나 접근 권한이 없어요."));
		if (accountType.equals(target.accountType())) {
			return;
		}
		requireRoom(links, accountType, brandId);
		linkRepository.updateAccountType(userId, brandId, accountType);
	}

	/**
	 * 삭제(§5-3) — 소유권 확인 후 해당 연결만 soft-delete하고, 같은 브랜드에 남은 활성 연결 수를 같은
	 * 트랜잭션에서 센다. 활성 연결이 없으면 남의 브랜드인지 이미 해제한 내 브랜드인지 구분할 수 없어 둘 다 403이다.
	 */
	@Transactional
	UnlinkResult unlink(long userId, long brandId) {
		BrandLinkRow link = linkRepository.findActiveByUserAndBrand(userId, brandId)
				.orElseThrow(() -> V1ApiException.forbidden("FORBIDDEN", "브랜드 계정을 찾을 수 없거나 접근 권한이 없어요."));
		linkRepository.softDeleteLink(userId, brandId);
		return new UnlinkResult(link.brandId(), link.username(), linkRepository.countActiveByBrand(brandId) == 0);
	}

	/**
	 * 회원 탈퇴 훅 — 유저의 활성 연결을 전부 해제하고 브랜드별 잔여 판정을 돌려준다.
	 * 연결이 없으면 빈 목록(할 일 없음).
	 */
	@Transactional
	List<UnlinkResult> unlinkAllForWithdrawal(long userId) {
		List<BrandLinkRow> links = linkRepository.findAllActiveByUser(userId);
		if (links.isEmpty()) {
			return List.of();
		}
		linkRepository.softDeleteAllActiveByUser(userId);
		return links.stream()
				.map(link -> new UnlinkResult(link.brandId(), link.username(),
						linkRepository.countActiveByBrand(link.brandId()) == 0))
				.toList();
	}

	/**
	 * 대상 타입에 자리가 있는지 — 없으면 409. {@code movingBrandId}는 타입을 옮기는 중인 브랜드로,
	 * 그 행은 아직 옛 타입이라 대상 타입 카운트에 들지 않지만 명시적으로 제외해 의도를 드러낸다
	 * (옛 타입 → 새 타입 이동이라 새 타입 쪽 자리만 보면 된다).
	 */
	private static void requireRoom(List<BrandLinkRow> links, String accountType, Long movingBrandId) {
		long used = links.stream()
				.filter(link -> movingBrandId == null || link.brandId() != movingBrandId)
				.filter(link -> accountType.equals(link.accountType()))
				.count();
		if (used >= BrandAccountType.limitOf(accountType)) {
			throw V1ApiException.conflict(BrandAccountType.limitCodeOf(accountType),
					BrandAccountType.limitMessageOf(accountType));
		}
	}

	/** 해제 결과 — lastLink=true면 이 브랜드의 마지막 활성 연결이었다(monitoring 탈퇴 대상, §5-3). */
	record UnlinkResult(long brandId, String username, boolean lastLink) {
	}
}
