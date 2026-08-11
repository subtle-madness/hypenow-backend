package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.auth.UserProfile;
import com.celfit.was.auth.UserRepository;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.MonitoringApiException;
import com.celfit.was.monitoring.MonitoringCommandClient;
import com.celfit.was.monitoring.MonitoringCommandClient.BrandRegisterResult;
import com.celfit.was.v1.common.V1ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 브랜드 계정 라이프사이클(스펙 §5, 08-07 다계정 개정) — 연결·목록·단건·삭제 + 회원 탈퇴 훅.
 * POST는 "브랜드 연결"이다: 브랜드는 전역 1회 수집이고(monitoring 등록이 멱등 replay), 여러 사용자가
 * 같은 브랜드에 연결해 수집 데이터를 공유한다. 이미 연결된 브랜드 재요청은 오류가 아니라 기존 객체
 * 반환(멱등)이고, 유저별 한도({@link BrandLinkTransaction#ACCOUNT_LIMIT}) 초과만 409다.
 *
 * <p>monitoring 호출은 항상 DB 트랜잭션 <b>밖</b>이다({@link BrandLinkTransaction}이 트랜잭션 경계).
 * 등록은 "monitoring 동기 검증 → was 커밋" 순서다(FE 명세와 의도적으로 다른 지점, 스펙 §2):
 * 존재하지 않는 계정명으로 연결 행을 만드는 사고를 막아야 한다.
 *
 * <p>monitoring 서브시스템이 꺼진 환경(monitoring.enabled=false)에서는 빈 자체가 뜨지 않는다 —
 * 컨트롤러도 같은 조건이라 표면이 통째로 사라진다(레거시 MonitoringRegistrationExecutor 관용구).
 */
@Service
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class V1BrandAccountService {

	private static final Logger log = LoggerFactory.getLogger(V1BrandAccountService.class);

	private final BrandLinkRepository linkRepository;
	private final BrandLinkTransaction linkTransaction;
	private final MonitoringCommandClient commandClient;
	private final BrandReadRepository brandReadRepository;
	private final BrandAccountAssembler assembler;
	private final UserRepository userRepository;

	public V1BrandAccountService(BrandLinkRepository linkRepository, BrandLinkTransaction linkTransaction,
			MonitoringCommandClient commandClient, BrandReadRepository brandReadRepository,
			BrandAccountAssembler assembler, UserRepository userRepository) {
		this.linkRepository = linkRepository;
		this.linkTransaction = linkTransaction;
		this.commandClient = commandClient;
		this.brandReadRepository = brandReadRepository;
		this.assembler = assembler;
		this.userRepository = userRepository;
	}

	/**
	 * 연결(§5-1, 08-07 다계정 개정) — 형식 검증 → 사전 판정(멱등·한도) → monitoring 동기 등록(멱등
	 * replay — 이미 수집 중·완료된 브랜드면 재수집 없이 기존 brandId) → was 연결 커밋 → 202 BrandAccount.
	 * 같은 계정명이 이미 연결돼 있으면 monitoring 호출 없이 기존 계정 객체를 그대로 돌려준다(멱등).
	 */
	public BrandAccountResponse register(long userId, String rawUsername) {
		String username = BrandUsername.normalize(rawUsername);
		BrandUsername.validate(username);
		Optional<Long> alreadyLinked = linkTransaction.precheck(userId, username);
		if (alreadyLinked.isPresent()) {
			return assembler.toResponse(findAccountOrThrow(alreadyLinked.get()));
		}

		String brandName = brandNameOf(userId);
		BrandRegisterResult registered = translate(() -> commandClient.registerBrand(username, brandName));
		try {
			linkTransaction.link(userId, registered.brandId(), username);
		} catch (RuntimeException e) {
			compensate(registered.brandId(), username);
			throw e;
		}
		// 등록 응답의 status는 monitoring이 "ACTIVE"로 하드코딩해 보내므로 준비 상태 판정에 쓸 수 없다 —
		// 상태는 항상 brand_account 조회가 정본이다(§5-2).
		return assembler.toResponse(findAccountOrThrow(registered.brandId()));
	}

	/** 목록(§5-2) — 유저의 활성 연결 전체(연결 순), 유저당 최대 {@link BrandLinkTransaction#ACCOUNT_LIMIT}건. */
	public List<BrandAccountResponse> list(long userId) {
		List<BrandAccountResponse> accounts = new ArrayList<>();
		for (BrandLinkRow link : linkRepository.findAllActiveByUser(userId)) {
			Optional<BrandAccountRow> row = brandReadRepository.findAccount(link.brandId());
			if (row.isEmpty()) {
				// 도달 불가(등록이 monitoring 먼저라 연결이 있으면 brand_account도 있다). 목록 전체를
				// 500으로 떨구는 대신 그 건만 빼고 돌려주고 로그로 드러낸다 — 폴링 화면이 죽지 않게.
				log.warn("활성 연결의 brand_account 부재 — 목록에서 제외 userId={}, brandId={}",
						userId, link.brandId());
				continue;
			}
			accounts.add(assembler.toResponse(row.get()));
		}
		return List.copyOf(accounts);
	}

	/** 단건 폴링(§5-2) — 소유권은 활성 연결로 검증(남의 brandId는 403). */
	public BrandAccountResponse get(long userId, long brandId) {
		requireOwnership(userId, brandId);
		return assembler.toResponse(findAccountOrThrow(brandId));
	}

	/**
	 * 삭제(§5-3) — 연결 soft-delete 후 그 브랜드의 마지막 사용자였으면 monitoring 탈퇴.
	 * {@code users.instgram_account_name}은 건드리지 않는다(불변 — 같은 계정 재등록만 허용).
	 */
	public void delete(long userId, long brandId) {
		BrandLinkTransaction.UnlinkResult unlinked = linkTransaction.unlink(userId, brandId);
		deregisterIfLast(unlinked, "브랜드 계정 삭제");
	}

	/**
	 * 회원 탈퇴 훅 — users 하드 삭제(CASCADE)가 brand_monitorings를 지워버리기 <b>전에</b> 불러야
	 * "마지막 사용자면 monitoring 탈퇴" 판정이 가능하다. 행이 먼저 사라지면 고아 브랜드가 매일
	 * 수집을 계속한다(정리할 근거가 영영 남지 않는다). 삭제 API와 같은 로직·같은 best-effort 규율 —
	 * 다계정이라 연결 전부를 해제하고 브랜드별로 판정한다.
	 */
	public void cleanupForAccountDeletion(long userId) {
		for (BrandLinkTransaction.UnlinkResult unlinked : linkTransaction.unlinkAllForWithdrawal(userId)) {
			deregisterIfLast(unlinked, "회원 탈퇴");
		}
	}

	/**
	 * monitoring 탈퇴 — best-effort다. 연결 해제는 이미 커밋됐으므로 여기서 예외를 올리면 재시도가
	 * 403(이미 해제됨)이라 복구 불능이 된다. 실패는 warn으로 남기고 진행한다(고아 브랜드는 로그로 추적).
	 *
	 * <p>{@code deregisterBrand}는 monitoring 404를 조용히 삼킨다(재시도 안전 설계) — 엔드포인트 부재
	 * (롤링 창·오설정)까지 성공으로 보이므로, 호출 전후로 info 로그를 남겨 실제 호출·완료를 관측 가능하게 한다.
	 */
	private void deregisterIfLast(BrandLinkTransaction.UnlinkResult unlinked, String reason) {
		if (!unlinked.lastLink()) {
			log.info("{} — 브랜드 연결만 해제(다른 활성 사용자가 남아 monitoring 유지) brandId={}",
					reason, unlinked.brandId());
			return;
		}
		String username = deregisterUsername(unlinked);
		try {
			log.info("{} — monitoring 브랜드 탈퇴 호출 brandId={}, username={}", reason, unlinked.brandId(), username);
			commandClient.deregisterBrand(username);
			log.info("{} — monitoring 브랜드 탈퇴 완료 brandId={}, username={}", reason, unlinked.brandId(), username);
		} catch (RuntimeException e) {
			log.warn("{} — monitoring 브랜드 탈퇴 실패(연결 해제는 유지, 고아 브랜드 수집 지속) brandId={}, username={}",
					reason, unlinked.brandId(), username, e);
		}
	}

	/**
	 * 탈퇴 API의 키는 username이다. 우리가 실제로 참조하는 것은 brandId이므로 정본인
	 * {@code brand_account}(brandId로 조회)의 username을 우선 쓴다 — was 링크의 username은 등록 시점
	 * 사본이라 어긋나면 엉뚱한 브랜드를 닫거나(동명 재사용) 아무것도 닫지 못한다.
	 * 조회가 비면(브랜드 행 부재) 사본으로 폴백한다 — 그 경우 monitoring은 404라 어차피 무해하다.
	 */
	private String deregisterUsername(BrandLinkTransaction.UnlinkResult unlinked) {
		try {
			return brandReadRepository.findAccount(unlinked.brandId())
					.map(BrandAccountRow::username)
					.orElse(unlinked.username());
		} catch (RuntimeException e) {
			log.warn("브랜드 계정 조회 실패 — 링크의 username 사본으로 탈퇴 진행 brandId={}", unlinked.brandId(), e);
			return unlinked.username();
		}
	}

	/**
	 * 등록 트랜잭션 실패 시 고아 brand_account 정리(§5-1 4단계) — best-effort.
	 * 다른 사용자가 같은 브랜드를 이미 보고 있으면 닫지 않는다(그쪽 수집이 끊긴다).
	 */
	private void compensate(long brandId, String username) {
		try {
			if (linkRepository.countActiveByBrand(brandId) > 0) {
				log.info("등록 롤백 — 다른 활성 사용자가 있어 monitoring 브랜드는 유지 brandId={}", brandId);
				return;
			}
			log.info("등록 롤백 — monitoring 브랜드 보상 탈퇴 brandId={}, username={}", brandId, username);
			commandClient.deregisterBrand(username);
		} catch (RuntimeException e) {
			// 보상 실패는 무해하다 — 같은 계정 재등록이 멱등 replay라 고아 행을 그대로 재사용한다.
			log.warn("등록 롤백 보상 탈퇴 실패(무해 — 재등록이 같은 행을 replay) brandId={}", brandId, e);
		}
	}

	/** 스펙 2026-08-11 §2 — company_name은 brand 유형일 때만 브랜드명(타 유형은 대행사명 등). */
	private String brandNameOf(long userId) {
		return userRepository.findProfileById(userId)
				.filter(p -> "brand".equals(p.userType()))
				.map(UserProfile::companyName)
				.filter(name -> name != null && !name.isBlank())
				.orElse(null);
	}

	private void requireOwnership(long userId, long brandId) {
		if (linkRepository.findActiveByUserAndBrand(userId, brandId).isEmpty()) {
			throw V1ApiException.forbidden("FORBIDDEN", "브랜드 계정을 찾을 수 없거나 접근 권한이 없어요.");
		}
	}

	private BrandAccountRow findAccountOrThrow(long brandId) {
		Optional<BrandAccountRow> row = brandReadRepository.findAccount(brandId);
		return row.orElseThrow(() -> V1ApiException.notFound("브랜드 계정을 찾을 수 없습니다."));
	}

	/**
	 * monitoring 에러 → FE 어휘 번역(스펙 §9). 404는 "IG에 그 계정이 없다"는 사용자 입력 문제라
	 * 404가 아니라 422로 올린다(FE 에러 표의 INSTAGRAM_ACCOUNT_NOT_FOUND). 422(비공개)는 code를
	 * 그대로 전달하되 메시지는 새로 쓴다 — MonitoringApiException.getMessage()가 "[CODE] 원문"으로
	 * 감싸져 있어 그대로 흘리면 내부 코드가 사용자에게 노출된다.
	 * 나머지(4xx·5xx)와 MonitoringUnavailableException은 V1ExceptionAdvice의 공통 매핑에 맡긴다 —
	 * 연결 불능 503은 다른 monitoring 엔드포인트와 같이 {@code Retry-After: 5}를 달아야 계약이 맞는다.
	 */
	private static <T> T translate(Supplier<T> call) {
		try {
			return call.get();
		} catch (MonitoringApiException e) {
			if (e.httpStatus() == 404) {
				throw new V1ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSTAGRAM_ACCOUNT_NOT_FOUND",
						"인스타그램에서 해당 계정을 찾을 수 없어요. 계정명을 다시 확인해 주세요.");
			}
			if (e.httpStatus() == 422) {
				throw new V1ApiException(HttpStatus.UNPROCESSABLE_CONTENT, e.code(),
						"비공개 계정이라 수집할 수 없어요. 공개 계정으로 전환한 뒤 다시 시도해 주세요.");
			}
			throw e;
		}
	}
}
