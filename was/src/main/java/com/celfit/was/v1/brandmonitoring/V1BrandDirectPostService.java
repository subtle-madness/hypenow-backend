package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandTaggedPostRow;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.monitoring.RegistrationEntryRow;
import com.celfit.was.monitoring.RegistrationRepository;
import com.celfit.was.monitoring.RegistrationResult;
import com.celfit.was.monitoring.RegistrationRow;
import com.celfit.was.monitoring.TargetRow;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.monitoring.ItemStatus;
import com.celfit.was.v1.monitoring.MonitoringInput;
import com.celfit.was.v1.monitoring.MonitoringRegistrationResponse;
import com.celfit.was.v1.monitoring.V1MonitoringRegistrationService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직접 등록(스펙 §6-4) — 태그 목록에 없는 게시물 URL을 사용자가 직접 붙여넣는 경로.
 *
 * <p>게시물 수집·추적 자체는 <b>레거시 등록 파이프라인이 정본</b>이다({@link V1MonitoringRegistrationService}).
 * 이 서비스가 하는 일은 앞뒤로 브랜드 문맥을 붙이는 것뿐이다:
 * <ol>
 *   <li>앞: 이미 내 브랜드 목록(유저 가시 창의 tagged ∪ direct 매핑)에 있는 게시물은 위임에서 빼고 duplicate</li>
 *   <li>뒤: 레거시가 만든(혹은 이미 갖고 있던) 추적 행에 {@code app.brand_direct_posts} 매핑을 건다</li>
 * </ol>
 * 레거시 서비스·테이블·응답은 한 줄도 바꾸지 않는다 — 호출과 조회만 한다.
 *
 * <p><b>위임 결과 매칭 키는 entry의 seq다.</b> 근거: 레거시 {@code register}가 돌려주는
 * {@code items}는 <i>새로 만들어진 pending 행만</i> 담아서(실패·중복 입력은 아예 빠진다) 내 요청
 * URL과 1:1 대응이 안 된다. 반면 {@code monitoring_registration_entries}는 입력마다 정확히 한 행이고
 * seq는 입력 순서 그대로다(레거시가 posts를 seq 0부터 순회, accounts는 그 뒤). 우리는 accounts를
 * 절대 보내지 않으므로 <b>seq == 위임 목록의 인덱스</b>가 성립한다. entry의 {@code input}은 원문
 * URL이라 같은 URL이 두 번 오면 구분이 안 되므로(정규화 전 문자열이 키가 못 된다) 보조 확인용으로만 쓴다.
 */
@Service
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class V1BrandDirectPostService {

	/** 레거시 monitoring_items.mode — url 모드로만 등록한다(계정 모드는 브랜드 표면의 관심사가 아니다). */
	private static final String MODE_URL = "url";

	/** 레거시와 같은 한 번 상한·기간 규칙 — 전건이 브랜드 중복이라 위임을 건너뛰는 경로에서도 검증이 필요해 여기서 한 번 더 본다. */
	private static final int MAX_POSTS = 100;
	private static final int MAX_TRACKING_DAYS = 90;

	private static final String BRAND_DUPLICATE_REASON = "이미 브랜드 목록에 있는 게시물입니다.";

	/**
	 * 종결 3종 — 이 상태의 행에는 매핑을 걸지 않는다. 레거시
	 * {@code V1MonitoringRegistrationService.TERMINAL_STATUSES}(private)와 같은 기준이며, 같은
	 * {@link ItemStatus#derive} 판정을 재사용한다: 레거시 duplicate 판정 모수에서 빠지는 상태이므로
	 * 여기서도 "지금 추적 중인 행"이 아니다(종결된 최신 행에 매핑이 걸리면 브랜드 화면이 죽은 행을 본다).
	 */
	private static final Set<String> TERMINAL_STATUSES =
			Set.of(ItemStatus.NOT_UPLOADED, ItemStatus.ENDED, ItemStatus.HIDDEN);

	private final BrandLinkRepository linkRepository;
	private final BrandReadRepository brandReadRepository;
	private final BrandDirectPostRepository directPostRepository;
	private final MonitoringItemRepository itemRepository;
	private final MonitoringReadRepository monitoringReadRepository;
	private final V1MonitoringRegistrationService legacyRegistration;
	private final RegistrationRepository registrationRepository;
	/** 중복 게이트의 창 컷 기준 시각 — 주입해야 테스트가 시한부가 되지 않는다(V1BrandPostsController와 같은 이유). */
	private final Clock clock;

	public V1BrandDirectPostService(BrandLinkRepository linkRepository, BrandReadRepository brandReadRepository,
			BrandDirectPostRepository directPostRepository, MonitoringItemRepository itemRepository,
			MonitoringReadRepository monitoringReadRepository,
			V1MonitoringRegistrationService legacyRegistration, RegistrationRepository registrationRepository,
			Clock clock) {
		this.linkRepository = linkRepository;
		this.brandReadRepository = brandReadRepository;
		this.directPostRepository = directPostRepository;
		this.itemRepository = itemRepository;
		this.monitoringReadRepository = monitoringReadRepository;
		this.legacyRegistration = legacyRegistration;
		this.registrationRepository = registrationRepository;
		this.clock = clock;
	}

	/**
	 * 접수(§6-4) — 202. 레거시 등록은 {@code @Transactional}이라 이 메서드의 트랜잭션에 합류한다:
	 * 등록 행·entry와 direct 매핑이 한 번에 커밋되고, 비동기 실행기 트리거도 그 커밋 뒤로 미뤄진다
	 * (레거시 {@code triggerExecutor}의 afterCommit 계약이 그대로 유지된다). monitoring DB 조회
	 * (tagged 윈도우)는 별도 커넥션이라 이 트랜잭션 밖이며, 읽기 전용이라 무해하다.
	 */
	@Transactional
	public BrandDirectRegistrationResponse register(long userId, long brandId, List<String> postUrls,
			Integer trackingDays, String campaignId) {
		BrandLinkRow link = requireOwnership(userId, brandId);
		List<String> urls = validatePostUrls(postUrls);
		int days = validateTrackingDays(trackingDays);

		Set<String> alreadyInBrand = brandShortCodes(userId, brandId, link.collectionMonths());

		// 1차 판정 — 입력 순서대로 (즉시 확정) 또는 (위임 대기)로 가른다.
		List<Plan> plans = new ArrayList<>(urls.size());
		List<String> delegated = new ArrayList<>();
		for (String url : urls) {
			plans.add(plan(url, alreadyInBrand, delegated));
		}
		if (delegated.isEmpty()) {
			// 위임할 게 없으면 레거시 등록 행 자체를 만들지 않는다 — 폴링할 대상이 없어 id도 없다.
			return new BrandDirectRegistrationResponse(null, KstTimestamps.toKstIso(OffsetDateTime.now()),
					plans.stream().map(Plan::entry).toList());
		}

		MonitoringRegistrationResponse legacy = legacyRegistration.register(userId, body(delegated, days, campaignId));
		long registrationId = Long.parseLong(legacy.registrationId());
		RegistrationRow row = registrationRepository.findById(registrationId)
				.orElseThrow(() -> new IllegalStateException("등록 직후 등록 행 조회 실패 id=" + registrationId));
		Map<Integer, RegistrationEntryRow> bySeq = new LinkedHashMap<>();
		for (RegistrationEntryRow entry : row.entries()) {
			bySeq.put(entry.seq(), entry);
		}

		List<BrandDirectRegistrationResponse.Entry> entries = new ArrayList<>(plans.size());
		for (Plan plan : plans) {
			entries.add(plan.delegatedIndex() == null
					? plan.entry()
					: settle(userId, brandId, plan, bySeq.get(plan.delegatedIndex())));
		}
		return new BrandDirectRegistrationResponse(legacy.registrationId(),
				KstTimestamps.toKstIso(row.requestedAt()), entries);
	}

	/**
	 * 등록 상태 조회(§6-4) — 레거시 entry 테이블이 상태의 산지지만, <b>브랜드 소속의 정본은
	 * {@code app.brand_direct_posts} 매핑</b>이다. 레거시 entry를 그대로 옮기면 접수 때 확정한 판정이
	 * 폴링 한 번에 뒤집히므로(아래 두 보정), 매핑을 함께 보고 조립한다. 남의 등록·없는 등록은 존재 여부를
	 * 흘리지 않고 똑같이 404다(레거시 {@code findById}에 유저 스코프가 없어 여기서 건다).
	 *
	 * <ul>
	 *   <li><b>success 승격 유지</b>: 접수는 "이미 레거시 추적 중"(레거시 duplicate)을 매핑 생성 후
	 *       success로 올린다. 레거시 entry는 영원히 duplicate·item_id null이라, 매핑이 있으면 여기서도
	 *       같은 승격을 다시 적용한다 — 그러지 않으면 FE 폴링이 화면을 duplicate로 되돌린다.</li>
	 *   <li><b>share 해소분 지연 매핑</b>: share 단축 링크는 접수 시점에 shortcode를 모르고, 해소·아이템
	 *       생성은 레거시 실행기가 나중에 한다({@code processShareEntry} — entry에 resolved_url·item_id를
	 *       채우고 success로 정산). 레거시엔 브랜드 훅이 없으므로(무수정 제약) 매핑이 영영 안 생겨
	 *       "성공했는데 브랜드 화면엔 없는" 유실이 된다. 그래서 resolved_url이 채워졌고 아이템이 확정된
	 *       entry는 이 조회에서 매핑을 만들어 준다. 브랜드는 {@link #resolveLazyMappingBrand} 판정
	 *       (같은 등록의 기존 매핑 → 단일 활성 연결 순)으로 정하고, 판정 불가면 매핑을 보류한다 —
	 *       entry 결과는 그대로 둔다.</li>
	 * </ul>
	 *
	 * <p>지연 매핑을 <b>share 해소분으로만</b> 한정한 이유: 일반 entry는 접수가 같은 트랜잭션에서 이미
	 * 매핑했으므로 여기서 다시 만들 게 없고, 범위를 넓히면 이 엔드포인트에 레거시(비브랜드) 등록 id를
	 * 넣는 것만으로 남의 화면이 아니라 <b>자기 캠페인 게시물이 브랜드 화면으로 끌려 들어온다</b>.
	 *
	 * <p>접수 때 위임에서 제외된 항목(이미 브랜드 목록에 있던 게시물)은 레거시 등록 행에 없으므로
	 * 여기 entries에도 나타나지 않는다 — 접수 응답이 그 판정의 유일한 전달 지점이다.
	 */
	@Transactional
	public BrandDirectRegistrationResponse get(long userId, String registrationId) {
		RegistrationRow row = registrationRepository.findById(parseRegistrationId(registrationId))
				.orElseThrow(V1BrandDirectPostService::registrationNotFound);
		if (row.userId() != userId) {
			throw registrationNotFound();
		}

		Map<String, Long> mappedItemIds = new LinkedHashMap<>();
		Map<String, Long> mappedBrandIds = new LinkedHashMap<>();
		for (BrandDirectPostRepository.Row mapping : directPostRepository.findByUser(userId)) {
			mappedItemIds.put(mapping.shortCode(), mapping.monitoringItemId());
			mappedBrandIds.put(mapping.shortCode(), mapping.brandId());
		}

		List<BrandDirectRegistrationResponse.Entry> entries = new ArrayList<>(row.entries().size());
		// 브랜드 판정은 지연 매핑이 실제로 필요할 때 한 번만 한다(판정 결과는 부재 포함 캐시).
		Long brandId = null;
		boolean brandResolved = false;
		for (RegistrationEntryRow entry : row.entries()) {
			// share 해소 결과(resolved_url)가 원문(input)보다 우선이다 — 단축 링크는 input을 파싱해도 null이다.
			String resolvedShortCode = shortCodeOf(entry.resolvedUrl());
			String shortCode = resolvedShortCode != null ? resolvedShortCode : shortCodeOf(entry.input());
			Long mappedItemId = shortCode == null ? null : mappedItemIds.get(shortCode);

			if (resolvedShortCode != null && entry.itemId() != null && mappedItemId == null) {
				if (!brandResolved) {
					brandId = resolveLazyMappingBrand(userId, row, mappedBrandIds);
					brandResolved = true;
				}
				if (brandId != null) {
					directPostRepository.upsert(userId, brandId, shortCode, entry.itemId());
					mappedItemId = entry.itemId();
				}
			}

			if (RegistrationResult.DUPLICATE.equals(entry.result()) && mappedItemId != null) {
				entries.add(entry(entry.input(), RegistrationResult.SUCCESS, null, null, shortCode, mappedItemId));
				continue;
			}
			Long itemId = entry.itemId() != null ? entry.itemId() : mappedItemId;
			entries.add(toEntry(entry.result(), entry.reasonCode(), entry.reason(), entry.input(), shortCode, itemId));
		}
		return new BrandDirectRegistrationResponse(String.valueOf(row.id()),
				KstTimestamps.toKstIso(row.requestedAt()), entries);
	}

	/**
	 * share 해소분 지연 매핑의 브랜드 판정(08-07 다계정 개정 — 등록 행에는 브랜드가 없다).
	 * ① 같은 등록의 다른 entry가 이미 매핑돼 있으면 그 브랜드 — 접수 트랜잭션이 확정한 값이라 정본이다
	 * (단, 복수 브랜드가 섞여 보이면 판정 불가로 취급). ② 힌트가 없으면 활성 연결이 정확히 1개일 때만
	 * 그 브랜드(대부분의 사용자). ③ 그 외(연결 없음·복수 연결에 힌트 없음)는 매핑을 보류한다 —
	 * entry 결과는 그대로 두므로 추적 자체는 유실되지 않고, 다음 폴링이 같은 판정을 재시도한다.
	 */
	private Long resolveLazyMappingBrand(long userId, RegistrationRow row, Map<String, Long> mappedBrandIds) {
		Set<Long> siblingBrands = new LinkedHashSet<>();
		for (RegistrationEntryRow entry : row.entries()) {
			String resolved = shortCodeOf(entry.resolvedUrl());
			String shortCode = resolved != null ? resolved : shortCodeOf(entry.input());
			Long mapped = shortCode == null ? null : mappedBrandIds.get(shortCode);
			if (mapped != null) {
				siblingBrands.add(mapped);
			}
		}
		if (siblingBrands.size() == 1) {
			return siblingBrands.iterator().next();
		}
		List<BrandLinkRow> links = linkRepository.findAllActiveByUser(userId);
		return links.size() == 1 ? links.get(0).brandId() : null;
	}

	// ---------- 1차 판정 ----------

	/**
	 * 입력 1건의 1차 판정. 위임 대상이면 {@code delegated}에 추가하고 그 인덱스를 기억한다 —
	 * 그 인덱스가 곧 레거시 entry의 seq다(클래스 주석의 매칭 근거).
	 */
	private static Plan plan(String url, Set<String> alreadyInBrand, List<String> delegated) {
		MonitoringInput parsed = MonitoringInput.parsePost(url);
		if (parsed instanceof MonitoringInput.Invalid invalid) {
			// 형식 오류는 레거시에 보내도 같은 판정이라 왕복 없이 여기서 확정한다(어휘는 레거시 것 재사용).
			return Plan.settled(entry(url, RegistrationResult.FAILED, invalid.reasonCode(), invalid.reason(),
					null, null));
		}
		// share 단축 링크는 해소 전이라 shortcode를 모른다 — 브랜드 중복 판정을 건너뛰고 레거시에 맡긴다
		// (해소 후 매핑은 상태 조회의 지연 매핑이 담당한다 — get() 문서 참조).
		String shortCode = parsed instanceof MonitoringInput.Post post ? post.shortCode() : null;
		if (shortCode != null && alreadyInBrand.contains(shortCode)) {
			return Plan.settled(entry(url, RegistrationResult.DUPLICATE, RegistrationResult.REASON_DUPLICATE,
					BRAND_DUPLICATE_REASON, shortCode, null));
		}
		int index = delegated.size();
		delegated.add(url);
		return new Plan(url, shortCode, index, null);
	}

	/**
	 * 이미 내 브랜드가 들고 있는 게시물 집합 — <b>유저 가시 창(링크 표시 창 ∩ 365일 자산 창)</b>의
	 * tagged + direct 매핑.
	 *
	 * <p>창을 자산 창이 아니라 유저 가시 창으로 잡는 이유(2026-08-17): 링크 창 밖 tagged는 목록에도
	 * 상세에도 없는데(같은 404) 중복으로 거절하면 {@code brand_direct_posts} 매핑이 영영 생기지 않아
	 * 그 게시물은 어떤 표면으로도 도달할 수 없다(데드엔드). 그렇게 등록된 창 밖 tagged는 병합의
	 * direct 우선 규칙 때문에 카드가 direct 셰이프가 되지만, <b>아예 안 보이는 것보다 낫다</b>는
	 * 판단이다(의도된 대가).
	 *
	 * <p>tagged는 그 창 안에서 <b>보강 정산 전까지 포함해 전량</b>을 본다(표시 게이트를 쓰지 않는다 —
	 * 2026-08-13 리뷰 결정은 그대로 유지된다). 미정산분을 중복으로 잡지 못하면 같은 게시물이 direct로
	 * 한 번 더 등록되고, {@code mergeByShortcode}의 direct 우선 규칙 때문에 그 카드가 정산 이후에도
	 * <b>영구히 direct 셰이프</b>로 고정된다(영상 URL·길이 null, 인증 배지 false).
	 */
	private Set<String> brandShortCodes(long userId, long brandId, int collectionMonths) {
		Set<String> codes = new LinkedHashSet<>(directPostRepository.shortCodesByUser(userId));
		// 두 창의 교집합 = 더 늦은 하한. 링크 창이 12여도 자산 창(365일) 밖은 애초에 서빙되지 않는다.
		LocalDate today = LocalDate.ofInstant(clock.instant(), KstTimestamps.KST);
		LocalDate windowStart = today.minusMonths(collectionMonths);
		LocalDate assetStart = today.minusDays(BrandPostAssembler.WINDOW_DAYS);
		OffsetDateTime cutoff = (windowStart.isAfter(assetStart) ? windowStart : assetStart)
				.atStartOfDay(KstTimestamps.KST).toOffsetDateTime();
		brandReadRepository
				.findTaggedPostsInWindow(brandId, cutoff)
				.stream()
				.map(BrandTaggedPostRow::shortCode)
				.forEach(codes::add);
		return codes;
	}

	// ---------- 위임 결과 정산 ----------

	/**
	 * 레거시 entry 하나를 브랜드 계약으로 옮기고, 아이템이 확정된 건에만 direct 매핑을 건다.
	 *
	 * <p>{@code duplicate}는 "이미 레거시로 추적 중"이다(§6-4 두 번째 분기). 레거시는 이 경우
	 * entry에 item_id를 남기지 않으므로(중복 판정은 행을 만들지 않는다) 아이템 id를 우리가 다시 찾아
	 * 매핑한 뒤 {@code success}로 올린다 — 사용자 관점에선 "브랜드 목록에 추가됨"이 맞기 때문이다.
	 * 아이템을 못 찾으면(경합·취소 등) 매핑할 대상이 없으니 duplicate 그대로 둔다.
	 */
	private BrandDirectRegistrationResponse.Entry settle(long userId, long brandId, Plan plan,
			RegistrationEntryRow entry) {
		if (entry == null) {
			// 도달 불가(레거시는 입력마다 entry 1행을 반드시 남긴다). 응답을 죽이지 않고 pending으로 흘린다.
			return entry(plan.input(), RegistrationResult.PENDING, null, null, plan.shortCode(), null);
		}
		if (RegistrationResult.DUPLICATE.equals(entry.result()) && plan.shortCode() != null) {
			Optional<Long> itemId = activeItemId(userId, plan.shortCode());
			if (itemId.isPresent()) {
				directPostRepository.upsert(userId, brandId, plan.shortCode(), itemId.get());
				return entry(plan.input(), RegistrationResult.SUCCESS, null, null, plan.shortCode(), itemId.get());
			}
			return entry(plan.input(), RegistrationResult.DUPLICATE, entry.reasonCode(), entry.reason(),
					plan.shortCode(), null);
		}
		if (entry.itemId() != null && plan.shortCode() != null) {
			directPostRepository.upsert(userId, brandId, plan.shortCode(), entry.itemId());
		}
		return toEntry(entry.result(), entry.reasonCode(), entry.reason(), plan.input(), plan.shortCode(),
				entry.itemId());
	}

	/**
	 * shortcode로 <b>진행 중인</b> 레거시 추적 행 id — 여러 개면 가장 최근 행(id 최대)을 쓴다. 같은
	 * 입력의 활성 행(canceled_at IS NULL)이 둘 이상 남을 수 있어서(종결 상태 뒤 재등록이 허용된다)
	 * id만 보고 고르면 <b>종결된 최신 행</b>에 매핑이 걸린다. 그래서 레거시 duplicate 판정과 같은
	 * 기준({@link ItemStatus#derive} + {@link #TERMINAL_STATUSES})으로 종결분을 먼저 걷어낸다.
	 */
	private Optional<Long> activeItemId(long userId, String shortCode) {
		List<MonitoringItemRow> candidates = itemRepository.findActiveByInput(userId, MODE_URL, shortCode);
		if (candidates.isEmpty()) {
			return Optional.empty();
		}
		Map<Long, TargetRow> targets = targetsOf(candidates);
		LocalDate today = LocalDate.now(KstTimestamps.KST);
		return candidates.stream()
				.filter(candidate -> !TERMINAL_STATUSES.contains(ItemStatus.derive(candidate,
						candidate.targetId() == null ? null : targets.get(candidate.targetId()), today)))
				.max(Comparator.comparingLong(MonitoringItemRow::id))
				.map(MonitoringItemRow::id);
	}

	/** 후보들의 target 배치 조회(N+1 방지) — target 미확정 행만 있으면 조회 자체를 돌지 않는다. */
	private Map<Long, TargetRow> targetsOf(List<MonitoringItemRow> candidates) {
		Set<Long> targetIds = candidates.stream()
				.map(MonitoringItemRow::targetId)
				.filter(Objects::nonNull)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if (targetIds.isEmpty()) {
			return Map.of();
		}
		return monitoringReadRepository.findTargets(targetIds).stream()
				.collect(Collectors.toMap(TargetRow::id, Function.identity(), (a, b) -> a));
	}

	// ---------- 매핑 ----------

	/** 게시물 링크에서 shortcode — share 단축 링크·형식 오류·계정 입력은 null. */
	private static String shortCodeOf(String url) {
		if (url == null) {
			return null;
		}
		return MonitoringInput.parsePost(url) instanceof MonitoringInput.Post post ? post.shortCode() : null;
	}

	/** 레거시 5종 result → 계약 4종. canceled는 FE 유니언에 없어 failed로 접고 사유로 구분한다. */
	private static BrandDirectRegistrationResponse.Entry toEntry(String result, String reasonCode, String reason,
			String input, String shortCode, Long itemId) {
		String normalized = RegistrationResult.CANCELED.equals(result) ? RegistrationResult.FAILED : result;
		return entry(input, normalized, reasonCode, reason, shortCode, itemId);
	}

	private static BrandDirectRegistrationResponse.Entry entry(String input, String result, String reasonCode,
			String reason, String shortCode, Long itemId) {
		return new BrandDirectRegistrationResponse.Entry(input, result, reasonCode, reason, shortCode,
				itemId == null ? null : String.valueOf(itemId));
	}

	/** 레거시 등록 본문 — posts만 담는다(accounts를 섞으면 seq ↔ 입력 인덱스 대응이 깨진다). */
	private static Map<String, Object> body(List<String> posts, int trackingDays, String campaignId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("posts", posts);
		body.put("trackingDays", trackingDays);
		if (campaignId != null && !campaignId.isBlank()) {
			body.put("campaignId", campaignId);
		}
		return body;
	}

	// ---------- 검증 ----------

	/**
	 * 연결 유무 + 구독 타입 검증. <b>경쟁사 구독 브랜드에는 직접 등록을 받지 않는다</b>(08-12) —
	 * 연결은 있으니 404·"권한 없음"이 아니라 별도 코드로 거절한다.
	 *
	 * <p>이유: 경쟁사 게시물은 캠페인에 붙이지 못하는데(v2 §3-3 차단), 직접 등록만 열려 있으면
	 * 그 경로로 레거시 추적 아이템이 생기고 캠페인 추가의 첫 분기(기존 아이템)를 그대로 통과해
	 * 차단이 무력화된다. 게다가 성과 대시보드는 같은 매핑({@code brand_direct_posts.brand_id} ×
	 * 링크의 accountType)을 근거로 그 콘텐츠를 경쟁사 콘텐츠로 보고 기본 범위에서 뺀다 —
	 * 등록만 허용하면 두 표면이 같은 콘텐츠를 두고 서로 다른 말을 한다.
	 *
	 * <p>지금 바꿔도 깨질 흐름이 없다: 타입은 08-12 신설이고 기존 연결은 전량 {@code own}으로
	 * 백필됐다(마이그레이션 기본값). 즉 아직 경쟁사 구독을 가진 유저 자체가 없다.
	 */
	private BrandLinkRow requireOwnership(long userId, long brandId) {
		BrandLinkRow link = linkRepository.findActiveByUserAndBrand(userId, brandId)
				.orElseThrow(() -> V1ApiException.forbidden("FORBIDDEN", "브랜드 계정을 찾을 수 없거나 접근 권한이 없어요."));
		if (BrandAccountType.COMPETITOR.equals(link.accountType())) {
			throw V1ApiException.forbidden("COMPETITOR_ACCOUNT_NOT_ALLOWED", "경쟁사 계정의 게시물은 추적 등록할 수 없어요.");
		}
		// 링크 행은 중복 게이트의 표시 창 판정에도 쓰인다(brandShortCodes) — 다시 조회하지 않는다.
		return link;
	}

	private static List<String> validatePostUrls(List<String> postUrls) {
		if (postUrls == null || postUrls.isEmpty()) {
			throw V1ApiException.validation("등록할 게시물을 입력해 주세요.");
		}
		if (postUrls.size() > MAX_POSTS) {
			throw V1ApiException.validation("한 번에 등록할 수 있는 항목은 최대 100개예요.");
		}
		return postUrls;
	}

	private static int validateTrackingDays(Integer trackingDays) {
		if (trackingDays == null) {
			throw V1ApiException.validation("모니터링 기간을 입력해 주세요.");
		}
		if (trackingDays < 1 || trackingDays > MAX_TRACKING_DAYS) {
			throw V1ApiException.validation("모니터링 기간은 1일 이상 90일 이하로 입력해 주세요.");
		}
		return trackingDays;
	}

	private static long parseRegistrationId(String raw) {
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			throw registrationNotFound();
		}
	}

	private static V1ApiException registrationNotFound() {
		return V1ApiException.notFound("등록 요청을 찾을 수 없습니다.");
	}

	/**
	 * 입력 1건의 1차 판정 결과 — {@code delegatedIndex}가 null이면 위임 없이 확정된 건(entry가 최종),
	 * 아니면 레거시 위임 목록에서의 인덱스(= entry seq)다.
	 */
	private record Plan(String input, String shortCode, Integer delegatedIndex,
			BrandDirectRegistrationResponse.Entry entry) {

		static Plan settled(BrandDirectRegistrationResponse.Entry entry) {
			return new Plan(entry.input(), entry.brandPostId(), null, entry);
		}
	}
}
