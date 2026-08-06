package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandTaggedPostRow;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.monitoring.RegistrationEntryRow;
import com.celfit.was.monitoring.RegistrationRepository;
import com.celfit.was.monitoring.RegistrationResult;
import com.celfit.was.monitoring.RegistrationRow;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.monitoring.MonitoringInput;
import com.celfit.was.v1.monitoring.MonitoringRegistrationResponse;
import com.celfit.was.v1.monitoring.V1MonitoringRegistrationService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직접 등록(스펙 §6-4) — 태그 목록에 없는 게시물 URL을 사용자가 직접 붙여넣는 경로.
 *
 * <p>게시물 수집·추적 자체는 <b>레거시 등록 파이프라인이 정본</b>이다({@link V1MonitoringRegistrationService}).
 * 이 서비스가 하는 일은 앞뒤로 브랜드 문맥을 붙이는 것뿐이다:
 * <ol>
 *   <li>앞: 이미 내 브랜드 목록(tagged 윈도우 ∪ direct 매핑)에 있는 게시물은 위임에서 빼고 duplicate</li>
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

	private final BrandLinkRepository linkRepository;
	private final BrandReadRepository brandReadRepository;
	private final BrandDirectPostRepository directPostRepository;
	private final MonitoringItemRepository itemRepository;
	private final V1MonitoringRegistrationService legacyRegistration;
	private final RegistrationRepository registrationRepository;

	public V1BrandDirectPostService(BrandLinkRepository linkRepository, BrandReadRepository brandReadRepository,
			BrandDirectPostRepository directPostRepository, MonitoringItemRepository itemRepository,
			V1MonitoringRegistrationService legacyRegistration, RegistrationRepository registrationRepository) {
		this.linkRepository = linkRepository;
		this.brandReadRepository = brandReadRepository;
		this.directPostRepository = directPostRepository;
		this.itemRepository = itemRepository;
		this.legacyRegistration = legacyRegistration;
		this.registrationRepository = registrationRepository;
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
		requireOwnership(userId, brandId);
		List<String> urls = validatePostUrls(postUrls);
		int days = validateTrackingDays(trackingDays);

		Set<String> alreadyInBrand = brandShortCodes(userId, brandId);

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
	 * 등록 상태 조회(§6-4) — 레거시 entry 테이블이 유일한 상태 저장소다. 남의 등록·없는 등록은
	 * 존재 여부를 흘리지 않고 똑같이 404다(레거시 {@code findById}에 유저 스코프가 없어 여기서 건다).
	 *
	 * <p>접수 때 위임에서 제외된 항목(이미 브랜드 목록에 있던 게시물)은 레거시 등록 행에 없으므로
	 * 여기 entries에도 나타나지 않는다 — 접수 응답이 그 판정의 유일한 전달 지점이다.
	 */
	public BrandDirectRegistrationResponse get(long userId, String registrationId) {
		RegistrationRow row = registrationRepository.findById(parseRegistrationId(registrationId))
				.orElseThrow(V1BrandDirectPostService::registrationNotFound);
		if (row.userId() != userId) {
			throw registrationNotFound();
		}
		List<BrandDirectRegistrationResponse.Entry> entries = row.entries().stream()
				.map(V1BrandDirectPostService::toEntry)
				.toList();
		return new BrandDirectRegistrationResponse(String.valueOf(row.id()),
				KstTimestamps.toKstIso(row.requestedAt()), entries);
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
		// share 단축 링크는 해소 전이라 shortcode를 모른다 — 브랜드 중복 판정을 건너뛰고 레거시에 맡긴다.
		String shortCode = parsed instanceof MonitoringInput.Post post ? post.shortCode() : null;
		if (shortCode != null && alreadyInBrand.contains(shortCode)) {
			return Plan.settled(entry(url, RegistrationResult.DUPLICATE, RegistrationResult.REASON_DUPLICATE,
					BRAND_DUPLICATE_REASON, shortCode, null));
		}
		int index = delegated.size();
		delegated.add(url);
		return new Plan(url, shortCode, index, null);
	}

	/** 이미 내 브랜드 화면에 있는 게시물 집합 — 목록 API와 같은 모수(90일 윈도우 tagged + direct 매핑). */
	private Set<String> brandShortCodes(long userId, long brandId) {
		Set<String> codes = new LinkedHashSet<>(directPostRepository.shortCodesByUser(userId));
		brandReadRepository
				.findTaggedPostsInWindow(brandId, BrandPostAssembler.windowCutoff(), BrandPostAssembler.TAGGED_LIMIT)
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
	 * shortcode로 진행 중인 레거시 추적 행 id — 여러 개면 가장 최근 행(id 최대)을 쓴다. 같은 입력의
	 * 활성 행이 둘 이상 남을 수 있고(종결 상태 뒤 재등록), 매핑은 지금 추적 중인 쪽을 가리켜야 한다.
	 */
	private Optional<Long> activeItemId(long userId, String shortCode) {
		return itemRepository.findActiveByInput(userId, MODE_URL, shortCode).stream()
				.max(Comparator.comparingLong(MonitoringItemRow::id))
				.map(MonitoringItemRow::id);
	}

	// ---------- 매핑 ----------

	private static BrandDirectRegistrationResponse.Entry toEntry(RegistrationEntryRow row) {
		MonitoringInput parsed = MonitoringInput.parsePost(row.input());
		String shortCode = parsed instanceof MonitoringInput.Post post ? post.shortCode() : null;
		return toEntry(row.result(), row.reasonCode(), row.reason(), row.input(), shortCode, row.itemId());
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

	private void requireOwnership(long userId, long brandId) {
		if (linkRepository.findActiveByUserAndBrand(userId, brandId).isEmpty()) {
			throw V1ApiException.forbidden("FORBIDDEN", "브랜드 계정을 찾을 수 없거나 접근 권한이 없어요.");
		}
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
