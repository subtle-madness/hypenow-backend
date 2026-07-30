package com.celfit.was.v1.monitoring;

import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.CampaignRow;
import com.celfit.was.monitoring.KeywordRule;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.RegistrationRepository;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.common.V1ApiException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * 등록 접수(6.27) 동기 구간 — 검증 → registration·entries·pending 행 생성 → 실행기 트리거 →
 * 즉시 응답. 실제 첫 확인(크롤)은 {@link RegistrationExecutor}(후속 태스크 구현)가 비동기로 한다.
 *
 * <p>중복 판정: 같은 유저의 진행 중(canceled_at IS NULL) 행과 겹치면 duplicate — target 상태
 * (예: EXPIRED류 종결 상태) 기반의 정교한 "진행 중" 재정의는 어셈블러 태스크에서 target 매핑이
 * 붙은 뒤 재방문한다(TODO, MonitoringItemRepository.findActiveByInput 참조).
 */
@Service
public class V1MonitoringRegistrationService {

	private static final int MAX_ITEMS = 100;
	private static final int MAX_TRACKING_DAYS = 90;
	private static final int MAX_KEYWORD_LIST_SIZE = 5;
	private static final String DUPLICATE_REASON_CODE = "duplicate";
	private static final String DUPLICATE_REASON = "이미 모니터링 중인 대상이에요.";
	private static final String KIND_POST = "post";
	private static final String KIND_ACCOUNT = "account";
	private static final String MODE_URL = "url";
	private static final String MODE_ACCOUNT = "account";
	private static final String RESULT_PENDING = "pending";
	private static final String RESULT_FAILED = "failed";
	private static final String RESULT_DUPLICATE = "duplicate";

	private final MonitoringItemRepository itemRepository;
	private final RegistrationRepository registrationRepository;
	private final CampaignRepository campaignRepository;
	private final V1CampaignService campaignService;
	private final RegistrationExecutor executor;
	private final ObjectMapper objectMapper;

	public V1MonitoringRegistrationService(MonitoringItemRepository itemRepository,
			RegistrationRepository registrationRepository, CampaignRepository campaignRepository,
			V1CampaignService campaignService, RegistrationExecutor executor, ObjectMapper objectMapper) {
		this.itemRepository = itemRepository;
		this.registrationRepository = registrationRepository;
		this.campaignRepository = campaignRepository;
		this.campaignService = campaignService;
		this.executor = executor;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public MonitoringRegistrationResponse register(long userId, Map<String, Object> body) {
		List<String> posts = asStringList(body.get("posts"), "posts");
		List<String> accounts = asStringList(body.get("accounts"), "accounts");
		if (posts.isEmpty() && accounts.isEmpty()) {
			throw V1ApiException.validation("등록할 게시물 또는 계정을 입력해 주세요.");
		}
		if (posts.size() + accounts.size() > MAX_ITEMS) {
			throw V1ApiException.validation("한 번에 등록할 수 있는 항목은 최대 100개예요.");
		}
		int trackingDays = parseTrackingDays(body.get("trackingDays"));

		ParsedKeywords parsedKeywords = parseKeywords(body.get("keywords"));
		if (!accounts.isEmpty()) {
			validateKeywordsRequired(parsedKeywords);
		}
		KeywordRule keywordRule = new KeywordRule(parsedKeywords.and(), parsedKeywords.or(), parsedKeywords.exclude());

		Object campaignIdRaw = body.get("campaignId");
		Object campaignNameRaw = body.get("campaignName");
		if (campaignIdRaw != null && campaignNameRaw != null) {
			throw V1ApiException.validation("campaignId와 campaignName을 동시에 지정할 수 없어요.");
		}

		Long campaignId = null;
		String campaignName = null;
		CampaignResponse newlyCreatedCampaign = null;
		if (campaignNameRaw != null) {
			V1CampaignService.Resolved resolved = campaignService.resolveOrCreate(userId, campaignNameRaw.toString());
			campaignId = resolved.row().id();
			campaignName = resolved.row().name();
			if (resolved.created()) {
				newlyCreatedCampaign = CampaignResponse.from(resolved.row());
			}
		} else if (campaignIdRaw != null) {
			CampaignRow campaign = campaignRepository.findByIdAndUser(parseCampaignId(campaignIdRaw), userId)
					.orElseThrow(() -> V1ApiException.notFound("캠페인을 찾을 수 없습니다."));
			campaignId = campaign.id();
			campaignName = campaign.name();
		}

		long registrationId = registrationRepository.insert(userId, trackingDays, campaignId);
		LocalDate registeredOn = LocalDate.now(KstTimestamps.KST);
		OffsetDateTime nextCheckAt = OffsetDateTime.now(KstTimestamps.KST).plusMinutes(5);
		RegistrationContext context =
				new RegistrationContext(registrationId, campaignId, campaignName, registeredOn, trackingDays, nextCheckAt);

		List<TrackingItemResponse> items = new ArrayList<>();
		Set<String> seenPostShortCodes = new HashSet<>();
		Set<String> seenAccountHandles = new HashSet<>();

		int seq = 0;
		for (String rawPost : posts) {
			processPost(userId, context, seq, rawPost, seenPostShortCodes, items);
			seq++;
		}
		for (String rawAccount : accounts) {
			processAccount(userId, context, seq, rawAccount, seenAccountHandles, keywordRule, items);
			seq++;
		}

		registrationRepository.markCompletedIfAllSettled(registrationId);
		triggerExecutor(registrationId);

		return MonitoringRegistrationResponse.of(registrationId, items, newlyCreatedCampaign);
	}

	/**
	 * 실행기 트리거는 접수 트랜잭션의 물리 커밋 이후로 미룬다(계약은 {@link RegistrationExecutor} 참조) —
	 * 비동기 실행기가 READ COMMITTED에서 아직 커밋 안 된 pending 행을 못 보는 경합을 막는다.
	 * 트랜잭션 동기화가 비활성(예: 트랜잭션 매니저가 없는 슬라이스 테스트)이면 즉시 호출로 대체한다.
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

	private void processPost(long userId, RegistrationContext context, int seq, String rawPost,
			Set<String> seenPostShortCodes, List<TrackingItemResponse> items) {
		MonitoringInput parsed = MonitoringInput.parsePost(rawPost);
		if (parsed instanceof MonitoringInput.Invalid invalid) {
			registrationRepository.insertEntry(context.registrationId(), seq, rawPost, KIND_POST, RESULT_FAILED,
					invalid.reasonCode(), invalid.reason(), null, null);
			return;
		}
		if (parsed instanceof MonitoringInput.ShareLink) {
			// share 링크는 리다이렉트 해소 전엔 실제 게시물을 특정할 수 없어 행을 만들지 않는다 —
			// TODO: share 해소 후 행 생성은 실행기 태스크(후속)에서 담당.
			registrationRepository.insertEntry(context.registrationId(), seq, rawPost, KIND_POST, RESULT_PENDING,
					null, null, null, null);
			return;
		}
		MonitoringInput.Post post = (MonitoringInput.Post) parsed;
		// Set.add()는 "이미 봤는지 확인"과 "지금 봤다고 기록"을 한 호출로 겸한다 — 이번 항목이 최초든
		// 아니든 항상 seen에 남겨야 이후 같은 값의 항목도 계속 duplicate로 잡힌다(부작용 의도적).
		if (!seenPostShortCodes.add(post.shortCode())
				|| !itemRepository.findActiveByInput(userId, MODE_URL, post.shortCode()).isEmpty()) {
			registrationRepository.insertEntry(context.registrationId(), seq, rawPost, KIND_POST, RESULT_DUPLICATE,
					DUPLICATE_REASON_CODE, DUPLICATE_REASON, null, null);
			return;
		}

		long itemId = itemRepository.insertPending(userId, MODE_URL, UUID.randomUUID(), context.campaignId(),
				post.shortCode(), post.canonicalUrl(), null, context.trackingDays(), context.registeredOn());
		registrationRepository.insertEntry(context.registrationId(), seq, rawPost, KIND_POST, RESULT_PENDING,
				null, null, null, itemId);
		items.add(TrackingItemResponse.pendingPost(itemId, post.canonicalUrl(), context.campaignId(),
				context.campaignName(), context.registeredOn(), context.trackingDays(), context.nextCheckAt()));
	}

	private void processAccount(long userId, RegistrationContext context, int seq, String rawAccount,
			Set<String> seenAccountHandles, KeywordRule keywordRule, List<TrackingItemResponse> items) {
		MonitoringInput parsed = MonitoringInput.parseAccount(rawAccount);
		if (parsed instanceof MonitoringInput.Invalid invalid) {
			registrationRepository.insertEntry(context.registrationId(), seq, rawAccount, KIND_ACCOUNT, RESULT_FAILED,
					invalid.reasonCode(), invalid.reason(), null, null);
			return;
		}
		MonitoringInput.Account account = (MonitoringInput.Account) parsed;
		// 위 processPost와 동일한 겸용 패턴 — add()가 "이미 봤는지"와 "지금 기록"을 동시에 처리한다.
		if (!seenAccountHandles.add(account.handle())
				|| !itemRepository.findActiveByInput(userId, MODE_ACCOUNT, account.handle()).isEmpty()) {
			registrationRepository.insertEntry(context.registrationId(), seq, rawAccount, KIND_ACCOUNT,
					RESULT_DUPLICATE, DUPLICATE_REASON_CODE, DUPLICATE_REASON, null, null);
			return;
		}

		String keywordsJson = writeKeywordsJson(keywordRule);
		long itemId = itemRepository.insertPending(userId, MODE_ACCOUNT, UUID.randomUUID(), context.campaignId(),
				account.handle(), null, keywordsJson, context.trackingDays(), context.registeredOn());
		registrationRepository.insertEntry(context.registrationId(), seq, rawAccount, KIND_ACCOUNT, RESULT_PENDING,
				null, null, null, itemId);
		items.add(TrackingItemResponse.pendingAccount(itemId, account.handle(), keywordRule, context.campaignId(),
				context.campaignName(), context.registeredOn(), context.trackingDays(), context.nextCheckAt()));
	}

	private String writeKeywordsJson(KeywordRule rule) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("and", rule.and());
		map.put("or", rule.any());
		map.put("exclude", rule.exclude());
		return objectMapper.writeValueAsString(map);
	}

	private static List<String> asStringList(Object raw, String field) {
		if (raw == null) {
			return List.of();
		}
		if (!(raw instanceof List<?> list)) {
			throw V1ApiException.validation(field + " 형식이 올바르지 않아요.");
		}
		List<String> result = new ArrayList<>();
		for (Object element : list) {
			if (!(element instanceof String value)) {
				throw V1ApiException.validation(field + " 형식이 올바르지 않아요.");
			}
			result.add(value);
		}
		return result;
	}

	private static int parseTrackingDays(Object raw) {
		if (raw == null) {
			throw V1ApiException.validation("모니터링 기간을 입력해 주세요.");
		}
		if (!(raw instanceof Integer) && !(raw instanceof Long)) {
			throw V1ApiException.validation("모니터링 기간은 정수로 입력해 주세요.");
		}
		long value = ((Number) raw).longValue();
		if (value < 1 || value > MAX_TRACKING_DAYS) {
			throw V1ApiException.validation("모니터링 기간은 1일 이상 90일 이하로 입력해 주세요.");
		}
		return (int) value;
	}

	private static long parseCampaignId(Object raw) {
		try {
			return Long.parseLong(raw.toString());
		} catch (NumberFormatException e) {
			throw V1ApiException.notFound("캠페인을 찾을 수 없습니다.");
		}
	}

	/** keywords 배열 원소 형식 검증은 accounts 존재 여부와 무관하게 항상 한다 — and·or 0개 규칙만 조건부(6.27). */
	private static ParsedKeywords parseKeywords(Object raw) {
		if (raw == null) {
			return new ParsedKeywords(List.of(), List.of(), List.of());
		}
		if (!(raw instanceof Map<?, ?> map)) {
			throw V1ApiException.validation("keywords 형식이 올바르지 않아요.");
		}
		return new ParsedKeywords(extractKeywordList(map, "and"), extractKeywordList(map, "or"),
				extractKeywordList(map, "exclude"));
	}

	private static List<String> extractKeywordList(Map<?, ?> map, String key) {
		Object raw = map.get(key);
		if (raw == null) {
			return List.of();
		}
		if (!(raw instanceof List<?> list)) {
			throw V1ApiException.validation("keywords 형식이 올바르지 않아요.");
		}
		List<String> result = new ArrayList<>();
		for (Object element : list) {
			if (!(element instanceof String value)) {
				throw V1ApiException.validation("keywords 형식이 올바르지 않아요.");
			}
			String trimmed = value.trim();
			if (!trimmed.isEmpty()) {
				result.add(trimmed);
			}
		}
		return result;
	}

	private static void validateKeywordsRequired(ParsedKeywords keywords) {
		if (keywords.and().size() + keywords.or().size() == 0) {
			throw V1ApiException.validation("감지 조건(and 또는 or)을 최소 1개 입력해 주세요.");
		}
		if (keywords.and().size() > MAX_KEYWORD_LIST_SIZE || keywords.or().size() > MAX_KEYWORD_LIST_SIZE
				|| keywords.exclude().size() > MAX_KEYWORD_LIST_SIZE) {
			throw V1ApiException.validation("감지 조건은 각 항목당 최대 5개까지 입력할 수 있어요.");
		}
	}

	private record ParsedKeywords(List<String> and, List<String> or, List<String> exclude) {
	}

	/** processPost/processAccount 공용 등록 컨텍스트 — 요청 1건 안에서 항목마다 반복되는 값 묶음. */
	private record RegistrationContext(long registrationId, Long campaignId, String campaignName,
			LocalDate registeredOn, int trackingDays, OffsetDateTime nextCheckAt) {
	}
}
