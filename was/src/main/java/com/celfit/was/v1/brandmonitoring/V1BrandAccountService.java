package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.auth.UserProfile;
import com.celfit.was.auth.UserRepository;
import com.celfit.was.monitoring.BrandHashtagTagRepository;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.MonitoringApiException;
import com.celfit.was.monitoring.MonitoringCommandClient;
import com.celfit.was.monitoring.MonitoringCommandClient.BrandRegisterResult;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.common.V1ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 브랜드 계정 라이프사이클(스펙 §5, 08-07 다계정 개정) — 연결·목록·단건·타입 변경(08-12)·삭제 + 회원 탈퇴 훅.
 * POST는 "브랜드 연결"이다: 브랜드는 전역 1회 수집이고(monitoring 등록이 멱등 replay), 여러 사용자가
 * 같은 브랜드에 연결해 수집 데이터를 공유한다. 이미 연결된 브랜드 재요청은 오류가 아니라 기존 객체
 * 반환(멱등)이고, 타입별 한도(own 6 / competitor 3 — {@link BrandAccountType}) 초과만 409다.
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
	private final BrandHashtagTagRepository hashtagTagRepository;

	public V1BrandAccountService(BrandLinkRepository linkRepository, BrandLinkTransaction linkTransaction,
			MonitoringCommandClient commandClient, BrandReadRepository brandReadRepository,
			BrandAccountAssembler assembler, UserRepository userRepository,
			BrandHashtagTagRepository hashtagTagRepository) {
		this.linkRepository = linkRepository;
		this.linkTransaction = linkTransaction;
		this.commandClient = commandClient;
		this.brandReadRepository = brandReadRepository;
		this.assembler = assembler;
		this.userRepository = userRepository;
		this.hashtagTagRepository = hashtagTagRepository;
	}

	/**
	 * 연결(§5-1, 08-07 다계정 개정) — 형식 검증 → 사전 판정(멱등·한도) → monitoring 동기 등록(멱등
	 * replay — 이미 수집 중·완료된 브랜드면 재수집 없이 기존 brandId) → was 연결 커밋 → 202 BrandAccount.
	 * 같은 계정명이 이미 연결돼 있으면 monitoring 호출 없이 기존 계정 객체를 그대로 돌려준다(멱등).
	 *
	 * <p>brandName은 own 연결일 때만 전달한다(#406 경쟁사 계정 타입 게이트, {@link #brandNameOf} 참고) —
	 * competitor 연결에 내 회사명을 넘기면 남의(경쟁사) 브랜드에 내 이름이 해시태그로 시드된다.
	 *
	 * <p>collectionMonths는 두 곳에 반영된다(2026-08-17 개정 — {@link BrandCollectionMonths}):
	 * <b>자산</b>(크롤 창)은 유저 간 max라 이미 연결된 브랜드 재요청도 <b>더 큰 값일 때만</b> 기간
	 * 확장으로 monitoring을 다시 부르고(2026-08-12 게이트, 축소 없음 — 수집된 사실이 정본),
	 * <b>링크</b>(유저 표시 창)는 명시한 값을 그대로 설정한다 — 축소도 반영되고 생략(null)은 불변이다.
	 *
	 * <p>재등록으로 타입이 바뀐 경우(precheck의 {@link BrandLinkTransaction.PrecheckResult#typeChanged}
	 * — 2026-08-19 경쟁사 판정 제거 설계 리뷰 결함 수정) own-link를 재계산해서 민다. 이 멱등 경로는
	 * 기간 확장이 없으면 monitoring 콜이 0이라(아래 if 블록 도달 불가) 타입 변경 자체로는 own-link가
	 * 갱신될 계기가 없다 — competitor→own 재등록이 이 신호 없이는 has_own_link=false로 영구 방치돼
	 * 기본값 안전 방향(과판정)이 깨진다. 타입 동일 단순 멱등 재-POST는 이 신호가 없어 push하지
	 * 않는다 — 기존 "monitoring 콜 0" 계약 그대로다.
	 */
	public BrandAccountResponse register(long userId, String rawUsername, String rawAccountType,
			Integer rawCollectionMonths) {
		String username = BrandUsername.normalize(rawUsername);
		BrandUsername.validate(username);
		String accountType = BrandAccountType.orDefault(rawAccountType);
		// 검증은 반드시 리포지토리 도달 전에 — 잘못된 값이 그대로 내려가면 CHECK 제약 위반이 500으로 샌다.
		if (!BrandAccountType.isValid(accountType)) {
			throw V1ApiException.validation("accountType 값이 올바르지 않아요.");
		}
		int months = BrandCollectionMonths.orDefault(rawCollectionMonths);
		if (!BrandCollectionMonths.isValid(months)) {
			throw V1ApiException.validation("collectionMonths 값이 올바르지 않아요.");
		}
		Optional<BrandLinkTransaction.PrecheckResult> alreadyLinked =
				linkTransaction.precheck(userId, username, accountType);
		if (alreadyLinked.isPresent()) {
			long brandId = alreadyLinked.get().brandId();
			if (alreadyLinked.get().typeChanged()) {
				pushOwnLinkSafely(brandId);
			}
			// 기간 확장(스펙 §3) — 자산 창보다 클 때만 monitoring 재호출. 사전 게이트일 뿐 정본 판정은
			// monitoring replay가 한 번 더 한다(경합으로 게이트가 낡아도 결과는 같다). 같거나 작은 값은
			// 현행 멱등 경로 그대로 monitoring 콜 0이다(축소 없음 — 수집된 사실이 정본).
			if (months > findAccountOrThrow(brandId).collectionMonths()) {
				String expandBrandName = BrandAccountType.OWN.equals(accountType) ? brandNameOf(userId) : null;
				translate(() -> commandClient.registerBrand(username, expandBrandName, months, accountType));
			}
			// 링크(유저 표시 창, 2026-08-17)는 명시한 값으로 그대로 — 축소 허용. 생략(null)은 불변이다:
			// orDefault로 접힌 12를 쓰면 필드 없는 구 클라이언트 재-POST가 신청 기간을 12로 되돌린다.
			if (rawCollectionMonths != null) {
				linkRepository.updateCollectionMonths(userId, brandId, months);
			}
			return get(userId, brandId);
		}

		String brandName = BrandAccountType.OWN.equals(accountType) ? brandNameOf(userId) : null;
		BrandRegisterResult registered =
				translate(() -> commandClient.registerBrand(username, brandName, months, accountType));
		try {
			// 링크에는 명시값(raw)을 넘긴다 — 개명 재등록이 기존 연결로 접힐 때 생략(null)과 명시를
			// 구분해야 한다(멱등 경로와 같은 규칙). 자산(monitoring)에는 위에서 orDefault한 months.
			linkTransaction.link(userId, registered.brandId(), username, accountType, rawCollectionMonths);
		} catch (RuntimeException e) {
			compensate(registered.brandId(), username);
			throw e;
		}
		// 태그 시딩(2026-08-27 해시태그 직접 수집 설계 §4, 2026-08-28 monitoring push 추가) — 신규
		// 링크에만 건다. 멱등 재-POST는 위 alreadyLinked 분기에서 이미 반환됐으므로 여기 도달하지
		// 않는다(지운 태그 부활 방지). 개명 재등록(precheck가 옛 계정명 기준이라 미스 나고 위
		// link()가 기존 brandId로 접히는 경우, 128행 주석 참고)은 이 경로를 그대로 지나간다 — 새
		// 계정명 유도 태그를 더할 뿐 기존 태그를 지우지 않으므로 "지운 태그 부활" 위험이 없어
		// 의도적으로 허용한다.
		seedLedgerTagsSafely(userId, registered.brandId(), username);
		// 등록 응답의 status는 monitoring이 "ACTIVE"로 하드코딩해 보내므로 준비 상태 판정에 쓸 수 없다 —
		// 상태는 항상 brand_account 조회가 정본이다(§5-2).
		return get(userId, registered.brandId());
	}

	/**
	 * 신규 링크 태그 시딩(2026-08-27 해시태그 직접 수집 설계 §4, <b>2026-08-28 태그 생성 권한 was
	 * 일원화</b>) — 계정명 유도 태그({@link BrandHashtagTags#derive})를 이 사용자의 장부에 남기고,
	 * <b>monitoring에도 일반 태그 add로 push</b>한다. 과거엔 monitoring
	 * {@code BrandRegistrationService.seedHashtagsSafely}가 등록·replay 양쪽에서 독립적으로
	 * {@code brand_hashtag}에 같은 태그를 심었다 — 태그 생성 권한이 두 시스템에 분산돼 있었다는
	 * 뜻이다. 그 자가 시드를 제거하고(monitoring 쪽 결정 기록 참조) was가 유일한 작성자가 되도록
	 * 이 메서드가 두 쓰기를 모두 담당한다.
	 *
	 * <p>push는 <b>일반 태그 add와 완전히 같은 경로</b>({@link MonitoringCommandClient#addHashtagTags})
	 * 다 — tombstone 재활성 의미론까지 포함한다. 즉 어떤 사용자가 이 브랜드에 새로 연결하면, 이전에
	 * 다른 사용자가 지웠던 자동 태그라도 이 사용자의 연결 의도(장부에 태그가 있어야 한다)를 따라
	 * 되살아난다 — 반면 그 태그를 지운 사용자 본인은 자기 장부에서 여전히 빠져 있으므로(사용자
	 * 스코프 격리) 계속 보호된다.
	 *
	 * <p>두 쓰기 모두 best-effort로 격리한다(등록 자체를 절대 실패시키지 않는다): monitoring push를
	 * 먼저 시도한다(태그 관리 API의 "monitoring 먼저" 관용구와 동형, {@link #putHashtagTags} 등
	 * 참조) — push가 실패해도 장부 쓰기는 <b>그대로 진행</b>한다. 링크는 이미 커밋됐고, 여기서
	 * 던지면 재시도가 멱등 경로(시딩 없음)로 접혀 그 사용자의 장부가 <b>영구히</b> 비어 버린다.
	 * 장부만 채워진 상태(push 실패)는 다음 사용자의 등록이 같은 태그를 다시 push하거나, 태그 관리
	 * API로 수동 추가하면 자연히 복구된다(장부 자체는 이미 정확하므로 "이 사용자에게 해시태그
	 * 게시물이 안 보임" 피해는 없다 — monitoring 스윕 대상에서만 빠질 뿐).
	 */
	private void seedLedgerTagsSafely(long userId, long brandId, String username) {
		List<String> derived = List.copyOf(BrandHashtagTags.derive(username));
		if (derived.isEmpty()) {
			return;
		}
		try {
			commandClient.addHashtagTags(username, derived);
		} catch (RuntimeException e) {
			log.warn("해시태그 자동 시드 monitoring push 실패(격리) — userId={}, brandId={}, username={}",
					userId, brandId, username, e);
		}
		try {
			hashtagTagRepository.addTags(userId, brandId, derived);
		} catch (RuntimeException e) {
			log.warn("해시태그 태그 장부 시딩 실패(격리) — userId={}, brandId={}", userId, brandId, e);
		}
	}

	/**
	 * 목록(§5-2) — 유저의 활성 연결 전체(연결 순). accountType은 연결 행에서 온다(08-12).
	 *
	 * <p>타입별 사용량({@link Listing#counts()})은 <b>반환 목록이 아니라 연결 행에서</b> 센다(08-12
	 * 리뷰): 한도를 강제하는 모수가 연결이고, brand_account 행이 없어 목록에서 빠진 연결도 자리는
	 * 그대로 차지한다. 목록에서 세면 FE가 "5 / 6"을 그려 놓고 다음 POST에서 409를 맞는다.
	 */
	public Listing list(long userId) {
		List<BrandLinkRow> links = linkRepository.findAllActiveByUser(userId);
		List<BrandAccountResponse> accounts = new ArrayList<>();
		for (BrandLinkRow link : links) {
			Optional<BrandAccountRow> row = brandReadRepository.findAccount(link.brandId());
			if (row.isEmpty()) {
				// 도달 불가(등록이 monitoring 먼저라 연결이 있으면 brand_account도 있다). 목록 전체를
				// 500으로 떨구는 대신 그 건만 빼고 돌려주고 로그로 드러낸다 — 폴링 화면이 죽지 않게.
				log.warn("활성 연결의 brand_account 부재 — 목록에서 제외 userId={}, brandId={}",
						userId, link.brandId());
				continue;
			}
			accounts.add(assembler.toResponse(row.get(), link.accountType(), link.collectionMonths()));
		}
		long own = links.stream().filter(link -> BrandAccountType.OWN.equals(link.accountType())).count();
		Map<String, Long> counts = new LinkedHashMap<>();
		counts.put(BrandAccountType.OWN, own);
		counts.put(BrandAccountType.COMPETITOR, links.size() - own);
		return new Listing(List.copyOf(accounts), counts);
	}

	/**
	 * 목록 응답 재료 — 표시용 계정 목록과 한도 게이트용 타입별 사용량을 함께 돌려준다.
	 * 둘의 모수가 다르므로(위 javadoc) 한 번의 조회에서 같이 내려 컨트롤러가 다시 세지 않게 한다.
	 *
	 * @param counts 키 순서 고정(own → competitor) — meta 직렬화 순서가 JVM마다 흔들리지 않게 LinkedHashMap
	 */
	public record Listing(List<BrandAccountResponse> accounts, Map<String, Long> counts) {
	}

	/** 단건 폴링(§5-2) — 소유권은 활성 연결로 검증(남의 brandId는 403). 타입도 그 연결에서 읽는다. */
	public BrandAccountResponse get(long userId, long brandId) {
		BrandLinkRow link = requireOwnership(userId, brandId);
		return assembler.toResponse(findAccountOrThrow(brandId), link.accountType(), link.collectionMonths());
	}

	/**
	 * 타입 변경(§2-3, 08-12) — 재수집 없이 구독 속성만 바꾼다. 상한 초과는 409, 남의 계정은 403.
	 * 상한 판정은 POST 재등록의 타입 변경(precheck 분기)과 같은 규칙을 공유한다
	 * ({@code BrandLinkTransaction.requireRoom} — 판정이 한 곳에만 있게). 트랜잭션 메서드는 서로 다르다.
	 *
	 * <p><b>{@code orDefault}를 쓰지 않는다</b>(08-12 리뷰): "생략 = own"은 등록(POST)의 하위 호환
	 * 규칙이고, PATCH에서 그대로 쓰면 필드를 안 보낸 요청이 계정을 조용히 own으로 덮어쓴다
	 * (경쟁사 강등, 심지어 own이 6개면 보내지도 않은 필드 때문에 409). 값 공간의 두 리터럴만 받고
	 * 부재·null·공백은 전부 400이다.
	 */
	public BrandAccountResponse changeType(long userId, long brandId, String rawAccountType) {
		// 검증은 반드시 리포지토리 도달 전에 — 잘못된 값이 그대로 내려가면 CHECK 제약 위반이 500으로 샌다.
		if (!BrandAccountType.isValid(rawAccountType)) {
			throw V1ApiException.validation("accountType 값이 올바르지 않아요.");
		}
		linkTransaction.changeType(userId, brandId, rawAccountType);
		pushOwnLinkSafely(brandId);
		return get(userId, brandId);
	}

	/**
	 * 해시태그 태그 셋 조회(태그 관리 API, 2026-08-12 — 08-19 사용자 스코프 개정, <b>2026-08-31
	 * 태그별 실행 상태 확장</b>) — 소유권은 단건 폴링과 동일(남의 brandId는 403). <b>태그 목록 자체의
	 * 정본은 여전히</b> {@code app.brand_hashtag_tags}(이 유저가 이 브랜드에 등록한 태그, 08-19
	 * 사용자 스코프 개정 그대로) — 남이 추가·삭제한 태그가 내 목록에 나타나거나 사라지면 안 된다.
	 *
	 * <p>다만 각 태그의 <b>실행 상태</b>(collecting|done|failed·lastRunAt·lastFoundCount)는 이
	 * 유저의 원장이 알 수 없는 정보라 monitoring을 호출해 병합한다({@link
	 * MonitoringCommandClient#getHashtagRunStates}) — "더 이상 monitoring을 호출하지 않는다"던 구
	 * 계약이 실행 상태 조회 목적으로만 재도입됐다(태그 목록 자체는 여전히 원장이 정본). 원장에는
	 * 있는데 monitoring 응답에 없는 태그(push 실패 드리프트, tombstone 등)는 collecting/lastRunAt=
	 * null/lastFoundCount=null로 접는다 — "아직 monitoring에 반영 안 됨"과 "실행 전"을 FE 입장에서
	 * 구분할 필요가 없다(둘 다 계속 폴링하면 된다).
	 *
	 * <p>monitoring 호출 자체가 실패해도(접속 불능 등) GET을 500/503으로 떨구지 않는다 — best-effort
	 * 로 격리하고 전체를 collecting/null/null로 접는다(로그만 warn) — 폴링 화면이 monitoring 순단
	 * 하나로 깨지면 안 된다(다른 monitoring best-effort push 관용구와 동형).
	 */
	public List<BrandHashtagTagsResponse.TagStatus> getHashtagTags(long userId, long brandId) {
		requireOwnership(userId, brandId);
		List<String> ledgerTags = List.copyOf(hashtagTagRepository.findByUserAndBrand(userId, brandId));
		if (ledgerTags.isEmpty()) {
			findAccountOrThrow(brandId);   // 소유권 통과 후에도 브랜드 자체는 존재해야 한다(기존 계약 유지)
			return List.of();
		}
		String username = findAccountOrThrow(brandId).username();
		Map<String, MonitoringCommandClient.TagRunState> runStates = fetchRunStatesSafely(username);
		List<BrandHashtagTagsResponse.TagStatus> result = new ArrayList<>();
		for (String tag : ledgerTags) {
			MonitoringCommandClient.TagRunState state = runStates.get(tag);
			result.add(state == null
					? new BrandHashtagTagsResponse.TagStatus(tag, "collecting", null, null)
					: new BrandHashtagTagsResponse.TagStatus(tag, state.status(),
							KstTimestamps.toKstIso(state.lastRunAt()), state.lastFoundCount()));
		}
		return result;
	}

	/** monitoring run-state 조회 best-effort 격리 — 실패하면 빈 맵(호출측이 전부 collecting으로 접는다). */
	private Map<String, MonitoringCommandClient.TagRunState> fetchRunStatesSafely(String username) {
		try {
			Map<String, MonitoringCommandClient.TagRunState> map = new LinkedHashMap<>();
			for (MonitoringCommandClient.TagRunState state : commandClient.getHashtagRunStates(username)) {
				map.put(state.tag(), state);
			}
			return map;
		} catch (RuntimeException e) {
			log.warn("해시태그 실행 상태 조회 실패(격리, 전체 collecting으로 폴백) — username={}", username, e);
			return Map.of();
		}
	}

	/**
	 * 태그 셋 전체 교체(08-19 사용자 스코프 개정) — <b>이 유저의 태그만</b> 교체한다. monitoring에는
	 * "이 유저의 새 태그 + 다른 유저들의 기존 태그"의 합집합을 PUT한다({@link #ensureSeeded}로 최초
	 * 시딩을 보장한 뒤 계산) — 그냥 내 새 태그만 PUT하면 monitoring의 브랜드 단위 태그 목록(PUT은
	 * 전체 교체 계약이다)이 다른 유저의 태그까지 통째로 사라지는 사고가 난다. monitoring 호출이
	 * 먼저다(검증·정규화는 monitoring 담당) — 실패하면 예외가 전파돼 원장은 건드리지 않는다.
	 */
	public void putHashtagTags(long userId, long brandId, List<String> tags) {
		requireOwnership(userId, brandId);
		String username = findAccountOrThrow(brandId).username();
		List<String> normalized = normalizeTags(tags);
		// PUT은 내 장부 전체 교체라 결과 장부 = normalized — 시딩 전에 검사해도 같은 답이다.
		requireWithinTagLimit(normalized.size());
		ensureSeeded(userId, brandId, username);

		Set<String> union = new LinkedHashSet<>(hashtagTagRepository.unionByBrand(brandId));
		union.removeAll(hashtagTagRepository.findByUserAndBrand(userId, brandId));
		union.addAll(normalized);
		commandClient.putHashtagTags(username, List.copyOf(union));

		hashtagTagRepository.replaceTags(userId, brandId, normalized);
	}

	/**
	 * 태그 셋 단건·다건 추가(2026-08-12, 08-19 사용자 스코프 개정) — tags null·빈 목록은 monitoring이
	 * 422로 거부한다(POST는 "추가할 게 없다"를 실수로 간주, PUT과 다른 규칙 — 이 경우 원장·시딩 둘 다
	 * 건드리지 않는다). 추가는 합집합에 원소를 더하는 것뿐이라 PUT과 달리 전체 재계산이 필요 없다.
	 */
	public void addHashtagTags(long userId, long brandId, List<String> tags) {
		requireOwnership(userId, brandId);
		String username = findAccountOrThrow(brandId).username();
		List<String> normalized = normalizeTags(tags);
		if (!normalized.isEmpty()) {
			ensureSeeded(userId, brandId, username);
			// POST는 합집합 추가라 결과 장부 = 내 장부(시딩 반영 후) ∪ normalized — 시딩 뒤에 세야
			// 자동 시드 태그가 자리를 차지한 상태로 판정된다(GET이 보여주는 수와 같은 모수).
			Set<String> resulting = new LinkedHashSet<>(hashtagTagRepository.findByUserAndBrand(userId, brandId));
			resulting.addAll(normalized);
			requireWithinTagLimit(resulting.size());
		}
		commandClient.addHashtagTags(username, normalized);
		hashtagTagRepository.addTags(userId, brandId, normalized);
	}

	/**
	 * 감지 해시태그 상한(2026-09-03, FE 피드백 09-01 #4-A) — 결과 장부가
	 * {@link BrandHashtagTags#MAX_TAGS_PER_USER}를 넘으면 400. monitoring 호출·장부 쓰기 전에 던진다
	 * (두 저장소 어느 쪽도 건드리지 않는다).
	 */
	private static void requireWithinTagLimit(int resultingSize) {
		if (resultingSize > BrandHashtagTags.MAX_TAGS_PER_USER) {
			throw V1ApiException.badRequest("HASHTAG_TAG_LIMIT_EXCEEDED",
					"감지 해시태그는 최대 " + BrandHashtagTags.MAX_TAGS_PER_USER + "개까지 등록할 수 있어요.");
		}
	}

	/**
	 * 태그 단건 삭제(2026-08-12, 08-19 사용자 스코프 개정) — 내 원장에서는 항상 지운다. monitoring
	 * 스윕 대상에서 실제로 빼는 건 <b>이 태그를 가진 다른 유저가 아무도 없을 때만</b>이다
	 * ({@link BrandHashtagTagRepository#hasOtherUserWithTag} — {@code BrandDirectPostRepository.
	 * hasOtherRegistrant}와 같은 패턴) — 안 그러면 내 삭제가 다른 유저의 스윕 대상에서 태그를 뺀다.
	 */
	public void deleteHashtagTag(long userId, long brandId, String tag) {
		requireOwnership(userId, brandId);
		String username = findAccountOrThrow(brandId).username();
		String normalized = normalizeTag(tag);
		if (normalized == null) {
			return;   // monitoring normalizeTagItem과 같은 관용구 — 정규화 후 빈 문자열은 대상 없음.
		}
		ensureSeeded(userId, brandId, username);
		hashtagTagRepository.deleteTag(userId, brandId, normalized);
		if (!hashtagTagRepository.hasOtherUserWithTag(brandId, normalized, userId)) {
			commandClient.deleteHashtagTag(username, normalized);
		}
	}

	/**
	 * 태그 전체 삭제(2026-08-12, 08-19 사용자 스코프 개정) — <b>이 유저의 태그만</b> 지운다("브랜드
	 * 태그 감지를 완전히 끈다"는 구 계약 폐기 — 다른 유저가 연결돼 있으면 그들의 스윕은 계속돼야
	 * 한다). monitoring의 브랜드 전체 삭제 API는 더 이상 쓰지 않는다(호출하면 다른 유저 태그까지
	 * 지워진다) — 내가 지운 태그 중 다른 소유자가 남지 않은 것만 단건 삭제로 반영한다.
	 */
	public void deleteAllHashtagTags(long userId, long brandId) {
		requireOwnership(userId, brandId);
		String username = findAccountOrThrow(brandId).username();
		ensureSeeded(userId, brandId, username);

		Set<String> myTags = hashtagTagRepository.findByUserAndBrand(userId, brandId);
		hashtagTagRepository.deleteAllTags(userId, brandId);
		for (String tag : myTags) {
			if (!hashtagTagRepository.hasOtherUserWithTag(brandId, tag, userId)) {
				commandClient.deleteHashtagTag(username, tag);
			}
		}
	}

	/**
	 * 무주 태그 승계(08-19 최초 시딩 → <b>2026-08-27 diff 개정</b>) — monitoring의 브랜드 단위 태그
	 * 중 <b>아무 사용자에게도 귀속되지 않은 것만</b> 조작 사용자에게 귀속시킨다.
	 *
	 * <p>구 규칙("이 브랜드 원장이 완전히 비었으면 monitoring 태그 전체 승계")은 태그 장부 백필
	 * (2026-08-27 설계 §4) 이후 <b>영영 발동하지 않는다</b> — 모든 활성 링크에 원장 행이 생기기
	 * 때문이다. 그러면 격리 개정 이전부터 monitoring에만 있던 무주 태그가 영구히 무주로 남아, 어느
	 * 사용자의 GET에도 나타나지 않고 관리도 불가능한 좀비가 된다. 조건을 "원장 비었나"에서 "이
	 * 태그의 소유자가 있나"로 좁히면 백필 뒤에도 08-19의 최초 조작자 승계 정책이 그대로 성립한다.
	 *
	 * <p>승계된 태그는 그 시점부터 조작 사용자의 태그이므로, 진행 중인 조작 자체의 대상이 된다 —
	 * {@link #putHashtagTags} 전체 교체·전체 삭제에 포함되는 것은 구 전량 승계와 동형이며 의도된
	 * 동작이다. 무주 태그가 PUT 전체 교체에서 monitoring으로부터 사라질 수 있는 것은 승계 방식과
	 * 무관한 08-19 계약(PUT=내 태그 전체 교체) 자체의 성질이다. 동시 쓰기 경합으로 같은 무주 태그가
	 * 두 사용자에게 이중 귀속될 수 있으나, 두 사용자가 각자 수동 추가한 것과 동일한 상태라
	 * 무해하다(과잉 귀속일 뿐 유실 없음).
	 *
	 * <p>대가로 태그 관리 쓰기 경로마다 monitoring GET이 1콜 나간다(구 구조는 원장이 있으면
	 * 건너뛰었다) — 사람이 누르는 저빈도 조작이라 수용한다. monitoring 태그가 0건이면 원장 조회도
	 * 하지 않는다.
	 */
	private void ensureSeeded(long userId, long brandId, String username) {
		List<String> current = normalizeTags(commandClient.getHashtagTags(username));
		if (current.isEmpty()) {
			return;
		}
		Set<String> owned = hashtagTagRepository.unionByBrand(brandId);
		List<String> unowned = current.stream().filter(tag -> !owned.contains(tag)).toList();
		if (!unowned.isEmpty()) {
			hashtagTagRepository.addTags(userId, brandId, unowned);
		}
	}

	/** 다건 정규화(trim → 선행 # 제거 → 소문자 → 중복 제거) — monitoring normalizeTags와 같은 규칙. */
	private static List<String> normalizeTags(List<String> tags) {
		if (tags == null) {
			return List.of();
		}
		LinkedHashSet<String> normalized = new LinkedHashSet<>();
		for (String tag : tags) {
			String cleaned = normalizeTag(tag);
			if (cleaned != null) {
				normalized.add(cleaned);
			}
		}
		return List.copyOf(normalized);
	}

	/** 단건 정규화 — null·blank는 null(호출측이 "대상 없음"으로 처리, monitoring normalizeTagItem 동형). */
	private static String normalizeTag(String tag) {
		if (tag == null) {
			return null;
		}
		String stripped = tag.strip();
		if (stripped.startsWith("#")) {
			stripped = stripped.substring(1);
		}
		String cleaned = stripped.strip().toLowerCase(Locale.ROOT);
		return cleaned.isBlank() ? null : cleaned;
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
			// 부분 해지(2026-08-19 경쟁사 판정 제거 설계 §3) — 해제된 연결이 own이었으면 브랜드의
			// own 연결 존재 여부가 바뀌었을 수 있다(마지막 own 연결이 방금 빠졌을 수도). 브랜드는
			// 남아 있으므로(monitoring 탈퇴 없음) 재계산해서 민다.
			pushOwnLinkSafely(unlinked.brandId());
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
	 * own-link 재계산·push(2026-08-19 경쟁사 판정 제거 설계 §3) — was 원장(app.brand_monitorings
	 * 활성 연결)에서 이 브랜드에 own 연결이 하나라도 남았는지 다시 읽어 monitoring에 절대값으로
	 * 민다. {@link #changeType}(양방향)·부분 해지({@link #deregisterIfLast}의 !lastLink 분기)가
	 * 호출한다 — 등록(register)은 요청 필드({@code accountType})로 이미 커버되므로 별도 push가
	 * 없다(설계 §2).
	 *
	 * <p>{@code deregisterBrand}와 같은 best-effort 컨벤션 — 실패해도 연결 변이는 이미 커밋됐으므로
	 * 예외를 올리지 않는다(warn 로그만). 드리프트(monitoring이 낡은 값을 들고 있는 상태)는 배포 후
	 * 수동 SQL 런북으로 복구한다(설계 §5) — 별도 재동기화 엔드포인트는 두지 않는다.
	 */
	private void pushOwnLinkSafely(long brandId) {
		Optional<String> username = brandReadRepository.findAccount(brandId).map(BrandAccountRow::username);
		if (username.isEmpty()) {
			log.warn("own-link push 스킵 — monitoring brand_account 부재 brandId={}", brandId);
			return;
		}
		try {
			boolean hasOwnLink = linkRepository.existsActiveOwnLink(brandId);
			commandClient.pushOwnLink(username.get(), hasOwnLink);
		} catch (RuntimeException e) {
			log.warn("own-link push 실패(격리, 드리프트는 수동 SQL 런북으로 복구) brandId={}", brandId, e);
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

	/**
	 * 스펙 2026-08-11 §2 — company_name은 brand 유형일 때만 브랜드명(타 유형은 대행사명 등).
	 *
	 * <p><b>경쟁사 연결에는 호출하지 않는다</b>(#406 경쟁사 계정 타입 게이트) — 여기서 나오는 값은
	 * 항상 "이 유저 자신의" 회사명이라, 경쟁사(competitor) 연결에 그대로 넘기면 내 브랜드명이
	 * 경쟁사 brand_account의 해시태그 셋에 시드된다. 그 브랜드를 공유하는 모든 사용자(다른 담당자
	 * 포함)에게 오염이 퍼지고, 태그 삭제 API가 없어 SQL 외 복구가 불가능하다. own 연결에서만 호출할 것.
	 */
	private String brandNameOf(long userId) {
		return userRepository.findProfileById(userId)
				.filter(p -> "brand".equals(p.userType()))
				.map(UserProfile::companyName)
				.filter(name -> name != null && !name.isBlank())
				.orElse(null);
	}

	private BrandLinkRow requireOwnership(long userId, long brandId) {
		return linkRepository.findActiveByUserAndBrand(userId, brandId)
				.orElseThrow(() -> V1ApiException.forbidden("FORBIDDEN", "브랜드 계정을 찾을 수 없거나 접근 권한이 없어요."));
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
