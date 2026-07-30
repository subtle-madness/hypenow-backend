package com.celfit.was.v1.monitoring;

import com.celfit.was.monitoring.KeywordRule;
import com.celfit.was.monitoring.MonitoringApiException;
import com.celfit.was.monitoring.MonitoringCommandClient;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.monitoring.MonitoringUnavailableException;
import com.celfit.was.monitoring.RegisterRequest;
import com.celfit.was.monitoring.RegisterResult;
import com.celfit.was.monitoring.RegistrationEntryRow;
import com.celfit.was.monitoring.RegistrationRepository;
import com.celfit.was.monitoring.RegistrationRow;
import com.celfit.was.monitoring.ShareResolveResult;
import com.celfit.was.v1.common.KstTimestamps;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * RegistrationExecutor의 실 구현 — 등록 접수(6.27) 커밋 이후 pending entry의 첫 확인(monitoring
 * 등록 호출)을 별도 스레드에서 수행한다. monitoring 서브시스템 활성 시에만 뜬다
 * ({@link NoopRegistrationExecutor}와 정확히 반대 조건 — 클래스 문서 참조).
 *
 * <h2>처리 대상 3종</h2>
 * <ul>
 *   <li><b>Post/Account(item_id 있음)</b> — 동기 구간이 이미 만든 pending monitoring_items 행을
 *   그대로 register 호출.</li>
 *   <li><b>ShareLink(item_id 없음)</b> — 동기 구간은 원본 URL만 entry에 남기고 행을 만들지 않는다
 *   (해소 전엔 대상을 특정할 수 없어서). 여기서 {@code resolveShare} → 중복 재검사 → 그제서야
 *   monitoring_items 행 생성 → register 순서로 처리한다.</li>
 * </ul>
 *
 * <h2>실패 분류</h2>
 * {@link MonitoringApiException}(확정 실패)은 행 삭제 + entry failed(코드 매핑),
 * {@link MonitoringUnavailableException}(전송 실패, 1회 재시도 후에도)은 행을 pending으로 남겨
 * {@link #recoverStalePending()}(추후 크론 배선)이 재시도하게 한다.
 */
@Component
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class MonitoringRegistrationExecutor implements RegistrationExecutor {

	private static final Logger log = LoggerFactory.getLogger(MonitoringRegistrationExecutor.class);

	/** findPendingOlderThan 기준 age — 실행기가 커밋 직후 잡지 못한(프로세스 재기동 등) pending 행 복구 임계값. */
	private static final Duration STALE_AGE = Duration.ofMinutes(5);

	private static final String RESULT_SUCCESS = "success";
	private static final String RESULT_FAILED = "failed";
	private static final String RESULT_DUPLICATE = "duplicate";
	private static final String RESULT_PENDING = "pending";

	private static final String MODE_ACCOUNT = "account";
	private static final String MODE_URL = "url";

	private static final String REASON_NOT_FOUND = "not_found";
	private static final String REASON_PRIVATE_ACCOUNT = "private_account";
	private static final String REASON_INVALID_FORMAT = "invalid_format";
	private static final String REASON_INTERNAL_ERROR = "internal_error";
	private static final String REASON_SHARE_UNRESOLVED = "share_link_unresolved";
	private static final String REASON_DUPLICATE = "duplicate";
	private static final String DUPLICATE_MESSAGE = "이미 모니터링 중인 대상이에요.";

	private final MonitoringCommandClient client;
	private final MonitoringItemRepository itemRepository;
	private final RegistrationRepository registrationRepository;
	private final ObjectMapper objectMapper;
	private final TaskExecutor executorPool;

	public MonitoringRegistrationExecutor(MonitoringCommandClient client, MonitoringItemRepository itemRepository,
			RegistrationRepository registrationRepository, ObjectMapper objectMapper,
			@Qualifier("monitoringRegistrationTaskExecutor") TaskExecutor executorPool) {
		this.client = client;
		this.itemRepository = itemRepository;
		this.registrationRepository = registrationRepository;
		this.objectMapper = objectMapper;
		this.executorPool = executorPool;
	}

	@Override
	public void submit(long registrationId) {
		executorPool.execute(() -> processSafely(registrationId));
	}

	/** 크래시 복구 — pending(target 미확정) 상태로 5분 넘게 방치된 monitoring_items를 같은 registrationKey로 replay한다.
	 * monitoring은 같은 키 재호출을 기존 target의 200 응답으로 처리하므로 멱등하다. 크론 배선은 후속 태스크. */
	public void recoverStalePending() {
		List<MonitoringItemRow> stale = itemRepository.findPendingOlderThan(STALE_AGE);
		for (MonitoringItemRow item : stale) {
			try {
				recoverItem(item);
			} catch (RuntimeException e) {
				log.error("pending 복구 실패 itemId={}", item.id(), e);
			}
		}
	}

	private void processSafely(long registrationId) {
		try {
			process(registrationId);
		} catch (RuntimeException e) {
			log.error("등록 백그라운드 처리 실패 registrationId={}", registrationId, e);
		}
	}

	private void process(long registrationId) {
		Optional<RegistrationRow> registrationOpt = registrationRepository.findById(registrationId);
		if (registrationOpt.isEmpty()) {
			log.warn("실행기 대상 registration 없음 id={}", registrationId);
			return;
		}
		RegistrationRow registration = registrationOpt.get();
		for (RegistrationEntryRow entry : registration.entries()) {
			if (!RESULT_PENDING.equals(entry.result())) {
				continue;
			}
			processEntry(registration, entry);
		}
		registrationRepository.markCompletedIfAllSettled(registrationId);
	}

	private void processEntry(RegistrationRow registration, RegistrationEntryRow entry) {
		if (entry.itemId() == null) {
			processShareEntry(registration, entry);
			return;
		}
		Optional<MonitoringItemRow> itemOpt = itemRepository.findByIdAndUser(entry.itemId(), registration.userId());
		if (itemOpt.isEmpty()) {
			// 사이 취소되는 등 이미 없어진 행 — 실행기가 할 일 없음(entry 원상태 그대로 둔다).
			log.warn("실행기 대상 item 없음 itemId={} registrationId={}", entry.itemId(), registration.id());
			return;
		}
		processItem(entry, itemOpt.get());
	}

	private void processItem(RegistrationEntryRow entry, MonitoringItemRow item) {
		if (item.canceledAt() != null) {
			// 접수(6.27)~백그라운드 첫 확인 사이에 6.30 cancel이 먼저 온 경우 — target 생성 없이
			// pending 그대로 종결(취소는 이미 markCanceled로 반영됨). entry는 success/failed/duplicate
			// 어휘에 "취소" 개념이 없어 원상태(pending) 그대로 둔다(위 itemOpt.isEmpty() 분기와 동일 패턴).
			log.info("취소된 행 — 백그라운드 등록 건너뜀 itemId={}", item.id());
			return;
		}
		RegisterRequest request = toRegisterRequest(item);
		try {
			RegisterResult result = registerWithRetry(request);
			itemRepository.confirmTarget(item.id(), result.targetId());
			registrationRepository.updateEntryResult(entry.registrationId(), entry.seq(), RESULT_SUCCESS, null, null,
					null, item.id());
		} catch (MonitoringApiException e) {
			itemRepository.delete(item.id());
			registrationRepository.updateEntryResult(entry.registrationId(), entry.seq(), RESULT_FAILED,
					mapReasonCode(e.code()), e.getMessage(), null, null);
		} catch (MonitoringUnavailableException e) {
			log.warn("등록 전송 실패 — pending 유지(복구 대상) itemId={}", item.id(), e);
		}
	}

	private void processShareEntry(RegistrationRow registration, RegistrationEntryRow entry) {
		ShareResolveResult resolved;
		try {
			resolved = resolveWithRetry(entry.input());
		} catch (MonitoringApiException e) {
			registrationRepository.updateEntryResult(entry.registrationId(), entry.seq(), RESULT_FAILED,
					mapShareReasonCode(e.code()), e.getMessage(), null, null);
			return;
		} catch (MonitoringUnavailableException e) {
			log.warn("share 해소 전송 실패 — pending 유지 input={}", entry.input(), e);
			return;
		}

		long userId = registration.userId();
		if (!itemRepository.findActiveByInput(userId, MODE_URL, resolved.shortCode()).isEmpty()) {
			registrationRepository.updateEntryResult(entry.registrationId(), entry.seq(), RESULT_DUPLICATE,
					REASON_DUPLICATE, DUPLICATE_MESSAGE, null, null);
			return;
		}

		Integer trackingDays = registration.trackingDays();
		if (trackingDays == null) {
			// V17 이전(마이그레이션 이전) 등록 잔재 방어 — 정상 플로우에선 등록 접수 시점에 항상 채워진다.
			log.error("registration에 trackingDays 없음 — share 처리 불가 registrationId={}", registration.id());
			registrationRepository.updateEntryResult(entry.registrationId(), entry.seq(), RESULT_FAILED,
					REASON_INTERNAL_ERROR, "등록 정보 결손(trackingDays 없음)", null, null);
			return;
		}
		LocalDate registeredOn = KstTimestamps.toKstDate(registration.requestedAt());
		String canonicalUrl = canonicalUrl(resolved);

		UUID itemKey = UUID.randomUUID();
		long itemId = itemRepository.insertPending(userId, MODE_URL, itemKey, registration.campaignId(),
				resolved.shortCode(), canonicalUrl, null, trackingDays, registeredOn);

		OffsetDateTime expiresAt = MonitoringExpiry.computeExpiresAt(registeredOn, trackingDays);
		try {
			RegisterResult result = registerWithRetry(RegisterRequest.post(itemKey, userId, resolved.shortCode(),
					expiresAt));
			itemRepository.confirmTarget(itemId, result.targetId());
			registrationRepository.updateEntryResult(entry.registrationId(), entry.seq(), RESULT_SUCCESS, null, null,
					canonicalUrl, itemId);
		} catch (MonitoringApiException e) {
			itemRepository.delete(itemId);
			registrationRepository.updateEntryResult(entry.registrationId(), entry.seq(), RESULT_FAILED,
					mapReasonCode(e.code()), e.getMessage(), null, null);
		} catch (MonitoringUnavailableException e) {
			log.warn("share 등록 전송 실패 — pending 유지(복구 대상) itemId={}", itemId, e);
			// entry는 pending 그대로 둔다 — item 행은 이미 생겼으니 recoverStalePending()이 같은 itemKey로 replay한다.
		}
	}

	private void recoverItem(MonitoringItemRow item) {
		// item.id는 먼저 확보 — MonitoringApiException 분기에서 delete()하면 FK(ON DELETE SET NULL)로
		// entries.item_id가 null이 돼 이후엔 역조회가 안 되므로, entry는 delete 전에 미리 찾아 둔다.
		Optional<RegistrationEntryRow> entryOpt = registrationRepository.findEntryByItemId(item.id());
		RegisterRequest request = toRegisterRequest(item);
		try {
			RegisterResult result = client.register(request);   // 멱등 replay 1회 — 실패는 다음 배치로 이월
			itemRepository.confirmTarget(item.id(), result.targetId());
			entryOpt.ifPresent(entry -> {
				registrationRepository.updateEntryResult(entry.registrationId(), entry.seq(), RESULT_SUCCESS, null,
						null, entry.resolvedUrl(), item.id());
				registrationRepository.markCompletedIfAllSettled(entry.registrationId());
			});
		} catch (MonitoringApiException e) {
			itemRepository.delete(item.id());
			entryOpt.ifPresent(entry -> {
				registrationRepository.updateEntryResult(entry.registrationId(), entry.seq(), RESULT_FAILED,
						mapReasonCode(e.code()), e.getMessage(), null, null);
				registrationRepository.markCompletedIfAllSettled(entry.registrationId());
			});
		} catch (MonitoringUnavailableException e) {
			log.warn("복구 replay 재실패 — 다음 배치로 이월 itemId={}", item.id(), e);
		}
	}

	private RegisterRequest toRegisterRequest(MonitoringItemRow item) {
		OffsetDateTime expiresAt = MonitoringExpiry.computeExpiresAt(item.registeredOn(), item.trackingDays());
		if (MODE_ACCOUNT.equals(item.mode())) {
			return RegisterRequest.account(item.registrationKey(), item.userId(), item.inputValue(),
					readKeywordRule(item.keywords()), expiresAt);
		}
		return RegisterRequest.post(item.registrationKey(), item.userId(), item.inputValue(), expiresAt);
	}

	/** 전송 실패(MonitoringUnavailableException)만 1회 재시도 — 확정 실패(MonitoringApiException)는 즉시 전파. */
	private RegisterResult registerWithRetry(RegisterRequest request) {
		try {
			return client.register(request);
		} catch (MonitoringUnavailableException first) {
			return client.register(request);
		}
	}

	private ShareResolveResult resolveWithRetry(String url) {
		try {
			return client.resolveShare(url);
		} catch (MonitoringUnavailableException first) {
			return client.resolveShare(url);
		}
	}

	/** 동기 구간(V1MonitoringRegistrationService.writeKeywordsJson)이 저장한 {and,or,exclude} 형태를
	 * 계약 KeywordRule(and,any,exclude)로 옮긴다 — 저장 키는 "or"지만 계약·모니터링 어휘는 "any"다. */
	private KeywordRule readKeywordRule(String keywordsJson) {
		Map<?, ?> map = objectMapper.readValue(keywordsJson, Map.class);
		return new KeywordRule(castList(map.get("and")), castList(map.get("or")), castList(map.get("exclude")));
	}

	@SuppressWarnings("unchecked")
	private static List<String> castList(Object raw) {
		return raw == null ? List.of() : (List<String>) raw;
	}

	private static String canonicalUrl(ShareResolveResult resolved) {
		String type = "REELS".equals(resolved.contentType()) ? "reel" : "p";
		return "https://www.instagram.com/" + type + "/" + resolved.shortCode() + "/";
	}

	private static String mapReasonCode(String monitoringCode) {
		return switch (monitoringCode) {
			case "SUBJECT_NOT_FOUND" -> REASON_NOT_FOUND;
			case "PRIVATE_ACCOUNT" -> REASON_PRIVATE_ACCOUNT;
			case "VALIDATION" -> REASON_INVALID_FORMAT;
			default -> REASON_INTERNAL_ERROR;
		};
	}

	private static String mapShareReasonCode(String monitoringCode) {
		return switch (monitoringCode) {
			case "SHARE_LINK_UNRESOLVED" -> REASON_SHARE_UNRESOLVED;
			case "SUBJECT_NOT_FOUND" -> REASON_NOT_FOUND;
			default -> REASON_INTERNAL_ERROR;
		};
	}
}
