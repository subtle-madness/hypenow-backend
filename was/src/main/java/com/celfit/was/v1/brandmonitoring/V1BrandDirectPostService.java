package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandPostCampaignRepository;
import com.celfit.was.monitoring.BrandPostRegistrationRepository;
import com.celfit.was.monitoring.BrandPostRegistrationRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandPoolStatusRow;
import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.monitoring.MonitoringCommandClient;
import com.celfit.was.monitoring.RegistrationResult;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.monitoring.MonitoringInput;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 직접 등록(2026-08-18 direct 통합 §T9, 설계 §2-2·§2-3·§2-4) — 브랜드 direct 게시물을
 * monitoring 브랜드 수집 파이프라인({@code brand_tagged_post.direct_registered_at})에 바로 등록한다.
 *
 * <p>레거시 위임 파이프라인({@code V1MonitoringRegistrationService}·{@code MonitoringItemRepository}·
 * {@code MonitoringReadRepository}·{@code RegistrationRepository})은 <b>완전히 끊는다</b> — 등록
 * 상태는 전용 저장소({@link BrandPostRegistrationRepository})가, 실제 monitoring 호출은 전용 실행기
 * ({@link BrandDirectRegistrationExecutor})가 담당한다. {@code brandId}가 등록 행 자체에 있어
 * 레거시의 share 해소분 브랜드 지연 추정({@code resolveLazyMappingBrand})이 통째로 불필요해진다.
 *
 * <p>FE 계약(§6 통지)은 바뀌지 않는다 — 202 접수 + 폴링 + 취소 3분기 셰이프는 그대로다. 바뀌는 것은
 * {@code trackingDays}가 검증만 되고 저장·사용되지 않는다는 점, {@code monitoringItemId}가 항상
 * null이라는 점, 취소가 매핑 hard delete가 아니라 브랜드 풀의 direct 표식 해제라는 점이다.
 */
@Service
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class V1BrandDirectPostService {

	/** 레거시와 같은 한 번 상한·기간 규칙 — trackingDays는 검증만 하고 저장·사용하지 않는다(설계 §5-1).
	 * 나이 티어 정책(BrandCrawlPolicy)이 direct 행에도 예외 없이 적용되므로 "등록 후 N일 추적" 개념이
	 * 없어졌다 — 그래도 하위 호환으로 1~90 검증은 유지한다(기존 FE 검증 회귀 방지). */
	private static final int MAX_POSTS = 100;
	private static final int MAX_TRACKING_DAYS = 90;

	private static final String BRAND_DUPLICATE_REASON = "이미 브랜드 목록에 있는 게시물입니다.";
	private static final String TAGGED_NOT_CANCELABLE_CODE = "TAGGED_POST_NOT_CANCELABLE";
	private static final String TAGGED_NOT_CANCELABLE_MESSAGE = "태그로 발견된 게시물은 취소할 수 없어요.";

	private final BrandLinkRepository linkRepository;
	private final BrandReadRepository brandReadRepository;
	private final BrandDirectPostRepository directPostRepository;
	private final BrandPostRegistrationRepository registrationRepository;
	private final CampaignRepository campaignRepository;
	private final BrandPostCampaignRepository postCampaignRepository;
	private final MonitoringCommandClient commandClient;
	private final BrandDirectRegistrationExecutor executor;
	/** 중복 게이트의 창 컷 기준 시각 — 주입해야 테스트가 시한부가 되지 않는다(V1BrandPostsController와 같은 이유). */
	private final Clock clock;

	public V1BrandDirectPostService(BrandLinkRepository linkRepository, BrandReadRepository brandReadRepository,
			BrandDirectPostRepository directPostRepository, BrandPostRegistrationRepository registrationRepository,
			CampaignRepository campaignRepository, BrandPostCampaignRepository postCampaignRepository,
			MonitoringCommandClient commandClient, BrandDirectRegistrationExecutor executor, Clock clock) {
		this.linkRepository = linkRepository;
		this.brandReadRepository = brandReadRepository;
		this.directPostRepository = directPostRepository;
		this.registrationRepository = registrationRepository;
		this.campaignRepository = campaignRepository;
		this.postCampaignRepository = postCampaignRepository;
		this.commandClient = commandClient;
		this.executor = executor;
		this.clock = clock;
	}

	/**
	 * 접수 — 202(설계 §2-2 register 7단계). 입력마다 우리가 직접 entry를 만든다(레거시의
	 * "seq == 위임 목록 인덱스" 암묵 규약이 필요 없다). Invalid·(1차 판정으로 확정되는) duplicate
	 * 입력은 즉시 정산되고, 나머지(신규 Post·share 단축 링크)는 pending으로 실행기에 넘어간다.
	 */
	@Transactional
	public BrandDirectRegistrationResponse register(long userId, long brandId, List<String> postUrls,
			Integer trackingDays, String campaignId) {
		BrandLinkRow link = requireOwnership(userId, brandId);
		List<String> urls = validatePostUrls(postUrls);
		validateTrackingDays(trackingDays);   // 검증만 — 저장하지 않는다(설계 §5-1)
		Long campaignIdLong = resolveCampaignId(userId, campaignId);

		Map<String, BrandPoolStatusRow> pool = poolStatus(brandId, urls);
		OffsetDateTime cutoff = duplicateCutoff(link.collectionMonths());

		BrandPostRegistrationRepository.InsertedRegistration inserted =
				registrationRepository.insert(userId, brandId, campaignIdLong);
		long registrationId = inserted.id();

		List<BrandDirectRegistrationResponse.Entry> entries = new ArrayList<>(urls.size());
		for (int seq = 0; seq < urls.size(); seq++) {
			entries.add(planEntry(registrationId, seq, urls.get(seq), pool, cutoff));
		}
		// 전건이 접수 시점에 확정됐으면(위임할 pending이 없으면) 완료 처리도 여기서 바로 끝낸다 —
		// 실행기가 나중에 같은 판정을 다시 해도 no-op이지만, 폴링이 completed_at을 즉시 봐야 한다.
		registrationRepository.markCompletedIfAllSettled(registrationId);
		triggerExecutor(registrationId);

		return new BrandDirectRegistrationResponse(String.valueOf(registrationId),
				KstTimestamps.toKstIso(inserted.requestedAt()), entries);
	}

	/**
	 * 입력 1건의 1차 판정 — Invalid·(창 안이거나 이미 direct인) duplicate는 여기서 확정하고, 신규
	 * Post·share 단축 링크는 pending으로 남겨 실행기가 처리하게 한다.
	 */
	private BrandDirectRegistrationResponse.Entry planEntry(long registrationId, int seq, String url,
			Map<String, BrandPoolStatusRow> pool, OffsetDateTime cutoff) {
		MonitoringInput parsed = MonitoringInput.parsePost(url);
		if (parsed instanceof MonitoringInput.Invalid invalid) {
			registrationRepository.insertEntry(registrationId, seq, url, null, RegistrationResult.FAILED,
					invalid.reasonCode(), invalid.reason());
			return entry(url, RegistrationResult.FAILED, invalid.reasonCode(), invalid.reason(), null);
		}
		String shortCode = parsed instanceof MonitoringInput.Post post ? post.shortCode() : null;
		if (shortCode != null) {
			BrandPoolStatusRow row = pool.get(shortCode);
			if (row != null && isDuplicate(row, cutoff)) {
				registrationRepository.insertEntry(registrationId, seq, url, shortCode, RegistrationResult.DUPLICATE,
						RegistrationResult.REASON_DUPLICATE, BRAND_DUPLICATE_REASON);
				return entry(url, RegistrationResult.DUPLICATE, RegistrationResult.REASON_DUPLICATE,
						BRAND_DUPLICATE_REASON, shortCode);
			}
		}
		// 신규 Post(중복 아님) 또는 share 단축 링크(shortCode 미상) — 실행기가 처리한다.
		registrationRepository.insertEntry(registrationId, seq, url, shortCode, RegistrationResult.PENDING, null,
				null);
		return entry(url, RegistrationResult.PENDING, null, null, shortCode);
	}

	/**
	 * 브랜드 풀 중복 판정(설계 §2-3) — duplicate ⟺ 행이 있고 (이미 direct 등록됐거나, 표시 창 안의
	 * tagged다). 현행(레거시) 대비 달라지는 점 3가지:
	 * <ol>
	 *   <li>유저의 다른 브랜드 direct 매핑을 모수에 넣지 않는다(크로스 브랜드 누수 제거) — 판정 자체가
	 *       {@code brandId} 스코프 쿼리({@link BrandReadRepository#findBrandPoolStatus})라 다른
	 *       브랜드 행은 애초에 후보에 없다.</li>
	 *   <li>창 밖 tagged는 duplicate가 아니라 승격 대상이다 — direct로 등록하면 direct_registered_at이
	 *       채워지고, direct 행은 표시 창 예외라 그 자리에서 보이기 시작한다(08-17 데드엔드 우회가
	 *       대가 없이 해소된다).</li>
	 *   <li>미정산 tagged를 중복으로 잡을 필요가 없어졌다 — 셰이프가 하나(brand_tagged_post 한 행)라
	 *       중복 등록해도 같은 행에 direct_registered_at만 붙을 뿐, 레거시처럼 카드가 direct 셰이프로
	 *       영구 고정되는 위험이 없다.</li>
	 * </ol>
	 */
	private static boolean isDuplicate(BrandPoolStatusRow row, OffsetDateTime cutoff) {
		return row.directRegistered() || !row.takenAt().isBefore(cutoff);
	}

	/** 링크 표시 창 ∩ 365일 자산 창의 하한(BrandPostAssembler.brandShortCodes와 동일 관용구). */
	private OffsetDateTime duplicateCutoff(int collectionMonths) {
		LocalDate today = LocalDate.ofInstant(clock.instant(), KstTimestamps.KST);
		LocalDate windowStart = today.minusMonths(collectionMonths);
		LocalDate assetStart = today.minusDays(BrandPostAssembler.WINDOW_DAYS);
		LocalDate cutoffDate = windowStart.isAfter(assetStart) ? windowStart : assetStart;
		return cutoffDate.atStartOfDay(KstTimestamps.KST).toOffsetDateTime();
	}

	/** 입력 중 shortCode를 아는(Post 파싱 성공) 것들만 배치 조회 — share 단축 링크는 해소 전이라 대상에서 빠진다. */
	private Map<String, BrandPoolStatusRow> poolStatus(long brandId, List<String> urls) {
		Set<String> shortCodes = new LinkedHashSet<>();
		for (String url : urls) {
			if (MonitoringInput.parsePost(url) instanceof MonitoringInput.Post post) {
				shortCodes.add(post.shortCode());
			}
		}
		return brandReadRepository.findBrandPoolStatus(brandId, shortCodes).stream()
				.collect(Collectors.toMap(BrandPoolStatusRow::shortCode, Function.identity(), (a, b) -> a));
	}

	/**
	 * 실행기 트리거는 접수 트랜잭션의 물리 커밋 이후로 미룬다(레거시 {@code V1MonitoringRegistrationService}와
	 * 같은 계약) — READ COMMITTED에서 비동기 실행기가 아직 커밋 안 된 pending 행을 못 보는 경합을 막는다.
	 */
	private void triggerExecutor(long registrationId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			executor.submit(registrationId);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				executor.submit(registrationId);
			}
		});
	}

	/**
	 * 등록 상태 조회(폴링) — {@code brand_post_registrations}를 유저 스코프로 읽어 entry를 그대로
	 * 옮긴다. <b>보정 로직 없음</b> — 레거시의 success 승격·share 지연 매핑이 전부 사라졌다(브랜드가
	 * 등록 행 자체에 있어 지연 추정이 필요 없고, 셰이프가 하나라 승격도 필요 없다). 남의 등록·없는
	 * 등록은 존재 여부를 흘리지 않고 똑같이 404다.
	 */
	public BrandDirectRegistrationResponse get(long userId, String registrationId) {
		BrandPostRegistrationRow row = registrationRepository.findById(parseRegistrationId(registrationId))
				.filter(r -> r.userId() == userId)
				.orElseThrow(V1BrandDirectPostService::registrationNotFound);
		List<BrandDirectRegistrationResponse.Entry> entries = row.entries().stream()
				.map(e -> entry(e.input(), e.result(), e.reasonCode(), e.reason(), e.shortCode()))
				.toList();
		return new BrandDirectRegistrationResponse(String.valueOf(row.id()), KstTimestamps.toKstIso(row.requestedAt()),
				entries);
	}

	// ---------- 취소(설계 §2-4) ----------

	/**
	 * 성과 측정 취소 — postId(shortcode) 기준, 매핑 삭제가 아니라 브랜드 풀의 direct 표식 해제다.
	 * 유저의 활성 브랜드 연결 전체를 순서대로 뒤져 그 풀에서 postId 행을 찾는다(같은 브랜드에 연결된
	 * 다른 유저가 등록한 게시물도 취소할 수 있다 — 설계 결정 1-1, 브랜드 풀은 공유 자원이다).
	 *
	 * <p><b>원격 실패를 삼키지 않는 이유</b>: 원장만 지우고 monitoring 호출이 실패하면, monitoring은
	 * 계속 그 게시물을 수집하는데 화면에서만 사라지는 불일치가 생긴다(레거시 {@code cancelLegacyIfPossible}
	 * 판단의 승계) — {@link MonitoringCommandClient#deleteDirectPost}가 던지면 그대로 전파해 취소
	 * 자체를 실패시킨다.
	 *
	 * <p>원격·원장 정리에 더해 {@link #settlePendingEntriesForCancel}로 같은 (brandId, shortCode)의
	 * pending 등록 entry도 함께 정산한다 — 취소-복구 경합 수정(2026-08-18 스테이징 실측) 참조.
	 */
	@Transactional
	public void cancel(long userId, String postId) {
		for (BrandLinkRow link : linkRepository.findAllActiveByUser(userId)) {
			List<BrandPoolStatusRow> rows = brandReadRepository.findBrandPoolStatus(link.brandId(), Set.of(postId));
			if (rows.isEmpty()) {
				continue;
			}
			BrandPoolStatusRow row = rows.get(0);
			if (!row.directRegistered()) {
				throw V1ApiException.badRequest(TAGGED_NOT_CANCELABLE_CODE, TAGGED_NOT_CANCELABLE_MESSAGE);
			}
			commandClient.deleteDirectPost(link.brandId(), postId);
			directPostRepository.delete(userId, postId);
			postCampaignRepository.deleteByBrandAndShortCode(link.brandId(), postId);
			settlePendingEntriesForCancel(link.brandId(), postId);
			return;
		}
		throw V1ApiException.notFound("대상을 찾을 수 없습니다.");
	}

	/**
	 * 취소-복구 경합 수정(2026-08-18 스테이징 실측) — 등록 명령의 HTTP 응답이 유실되면 monitoring은
	 * 실제로 등록을 완료했는데 was entry는 pending으로 남는다. 그 상태에서 사용자가 게시물을
	 * 취소하면(이 메서드), 방금 지운 (brandId, shortCode)와 같은 pending entry가 여전히 남아 있다가
	 * 5분 뒤 {@link BrandDirectRegistrationExecutor#recoverStalePending()}가 그 entry를 다시 집어
	 * 취소된 게시물을 재등록해 버리는 경합이 스테이징에서 실제로 재현됐다. 취소 시점에 그 entry를
	 * success로 정산해(등록 자체는 실제로 완료됐었다는 사실을 그대로 반영 — 그 뒤 취소는 별개
	 * 수명주기다) 복구 재시도 대상에서 구조적으로 제거한다. 이 정산이 없어도
	 * {@code isAlreadyDirectRegistered} 재검사가 살아있는 동안은 재등록을 duplicate로 막아주지만,
	 * 취소가 브랜드 풀에서 direct 표식을 지운 뒤이므로 그 재검사도 더 이상 막아주지 못한다 — 그래서
	 * 취소 시점 정산이 반드시 필요하다.
	 */
	private void settlePendingEntriesForCancel(long brandId, String shortCode) {
		for (Long registrationId : registrationRepository.settlePendingAsSuccessForCancel(brandId, shortCode)) {
			registrationRepository.markCompletedIfAllSettled(registrationId);
		}
	}

	// ---------- 검증·권한 ----------

	/**
	 * 연결 유무 + 구독 타입 검증 — <b>경쟁사 구독 브랜드에는 직접 등록을 받지 않는다</b>(08-12,
	 * 레거시와 동일 규칙 승계). 경쟁사 게시물이 등록되면 캠페인 차단(v2 §3-3)이 무력화되고, 성과
	 * 대시보드의 경쟁사 콘텐츠 판정과도 어긋난다.
	 */
	private BrandLinkRow requireOwnership(long userId, long brandId) {
		BrandLinkRow link = linkRepository.findActiveByUserAndBrand(userId, brandId)
				.orElseThrow(() -> V1ApiException.forbidden("FORBIDDEN", "브랜드 계정을 찾을 수 없거나 접근 권한이 없어요."));
		if (BrandAccountType.COMPETITOR.equals(link.accountType())) {
			throw V1ApiException.forbidden("COMPETITOR_ACCOUNT_NOT_ALLOWED", "경쟁사 계정의 게시물은 추적 등록할 수 없어요.");
		}
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

	private static void validateTrackingDays(Integer trackingDays) {
		if (trackingDays == null) {
			throw V1ApiException.validation("모니터링 기간을 입력해 주세요.");
		}
		if (trackingDays < 1 || trackingDays > MAX_TRACKING_DAYS) {
			throw V1ApiException.validation("모니터링 기간은 1일 이상 90일 이하로 입력해 주세요.");
		}
	}

	/** campaignId 소유 검증(설계 §register ③) — 비었으면 null, 형식 오류·타인 소유·부재는 전부 404. */
	private Long resolveCampaignId(long userId, String campaignId) {
		if (campaignId == null || campaignId.isBlank()) {
			return null;
		}
		long parsed;
		try {
			parsed = Long.parseLong(campaignId);
		} catch (NumberFormatException e) {
			throw V1ApiException.notFound("캠페인을 찾을 수 없습니다.");
		}
		CampaignRow campaign = campaignRepository.findByIdAndUser(parsed, userId)
				.orElseThrow(() -> V1ApiException.notFound("캠페인을 찾을 수 없습니다."));
		return campaign.id();
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

	/** monitoringItemId는 항상 null이다(§6 FE 통지 #3) — 레거시 아이템이 생성되지 않는다. */
	private static BrandDirectRegistrationResponse.Entry entry(String input, String result, String reasonCode,
			String reason, String shortCode) {
		return new BrandDirectRegistrationResponse.Entry(input, result, reasonCode, reason, shortCode, null);
	}
}
