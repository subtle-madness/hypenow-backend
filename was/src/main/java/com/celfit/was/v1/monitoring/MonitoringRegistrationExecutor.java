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
import com.celfit.was.monitoring.RegistrationResult;
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
import java.util.concurrent.RejectedExecutionException;
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

	private static final String MODE_ACCOUNT = "account";
	private static final String MODE_URL = "url";

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
		try {
			executorPool.execute(() -> processSafely(registrationId));
		} catch (RejectedExecutionException e) {
			// 풀(코어 2)+큐(100)가 모두 찬 경우 AbortPolicy가 여기로 던진다. submit()은 접수 트랜잭션의
			// afterCommit 콜백으로 요청 스레드에서 도는 경로라(RegistrationExecutor 인터페이스 문서 참조),
			// CallerRunsPolicy였다면 웹 스레드가 큐 소진분을 대신 처리하느라 최대 awaitTerminationSeconds급
			// (수 초~수십 초)으로 블로킹될 수 있었다. 즉시 실패시키고 이 등록은 pending인 채로 남겨
			// recoverStalePending()이 다음 배치에서 집어가게 한다 — 유실이 아니라 지연일 뿐이다.
			log.warn("등록 실행기 큐 초과 — 접수는 유지, pending 상태로 recoverStalePending 대기 registrationId={}",
					registrationId, e);
		}
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

	/**
	 * 나이 기반 entry 확정(트랙 LL §4-3) — {@code age}보다 오래(requested_at 기준) pending인 entry를
	 * item 실제 상태로 확정한다({@link RegistrationRepository#settleStaleEntries} 참조). 이건
	 * "재시도를 시도할 대상"을 고르는 {@link #recoverStalePending()}과는 다른 축이다: recover는
	 * "다시 시도해볼" 대상을, 이건 "그만 시도하고 상태에 맞춰 못 박을" 대상을 고른다.
	 *
	 * <p><b>호출 순서 불변식</b> — 반드시 {@link #recoverStalePending()} 다음에 호출해야 한다
	 * (호출부: {@code RecoverStalePendingScheduler}). 역순이면 아직 recover로 살릴 수 있는 pending
	 * item을 이 스윕이 먼저 failed로 확정해 버릴 수 있다 — age 임계(기본 24시간)는 recover 주기(기본
	 * 10분)보다 훨씬 크게 잡아 두 스윕이 서로 다른 항목을 보게 설계했지만, 순서 자체는 그 크기 차이에
	 * 기대지 않고 항상 지켜야 하는 불변식이다.
	 */
	public void settleStaleRegistrationEntries(Duration age) {
		List<Long> registrationIds = registrationRepository.settleStaleEntries(age);
		for (Long registrationId : registrationIds) {
			registrationRepository.markCompletedIfAllSettled(registrationId);
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
			if (!RegistrationResult.PENDING.equals(entry.result())) {
				continue;
			}
			try {
				processEntry(registration, entry);
			} catch (RuntimeException e) {
				// entry 하나의 예외가 나머지 entry 처리·완료 마킹까지 끌고 내려가지 않게 격리한다
				// (recoverStalePending의 per-item try-catch와 대칭). 원인 불명 예외는 재시도 여지가
				// 있으니 failed 대신 pending 유지 — 다음 실행 또는 recoverStalePending()이 다시 본다.
				log.error("entry 처리 중 예외 — pending 유지(재시도 여지) registrationId={} seq={}",
						registration.id(), entry.seq(), e);
			}
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
			// 접수(6.27)~백그라운드 첫 확인 사이에 6.30 cancel이 먼저 온 경우 — target 생성 없이 건너뛰고
			// entry를 canceled로 정산한다(트랙 LL, §4-2 — 예전엔 pending 그대로 방치해 영구 미완료로
			// 남았다). settleCanceledByItem은 result='pending' 조건부 UPDATE라 이미 다른 경로로 정산된
			// entry는 건드리지 않는다.
			log.info("취소된 행 — 백그라운드 등록 건너뛰고 entry 정산 itemId={}", item.id());
			registrationRepository.settleCanceledByItem(item.id())
					.ifPresent(registrationRepository::markCompletedIfAllSettled);
			return;
		}
		RegisterRequest request = toRegisterRequest(item);
		try {
			RegisterResult result = registerWithRetry(request);
			itemRepository.confirmTarget(item.id(), result.targetId());
			registrationRepository.updateEntryResult(entry.registrationId(), entry.seq(), RegistrationResult.SUCCESS,
					null, null, null, item.id());
		} catch (MonitoringApiException e) {
			itemRepository.delete(item.id());
			registrationRepository.updateEntryResult(entry.registrationId(), entry.seq(), RegistrationResult.FAILED,
					mapReasonCode(e.code()), e.getMessage(), null, null);
		} catch (MonitoringUnavailableException e) {
			log.warn("등록 전송 실패 — pending 유지(복구 대상) itemId={}", item.id(), e);
		}
	}

	private void processShareEntry(RegistrationRow registration, RegistrationEntryRow entry) {
		ShareResolveResult resolved;
		try {
			resolved = resolveWithRetry(entry.input(), registration.userId());
		} catch (MonitoringApiException e) {
			registrationRepository.updateEntryResult(entry.registrationId(), entry.seq(), RegistrationResult.FAILED,
					mapShareReasonCode(e.code()), e.getMessage(), null, null);
			return;
		} catch (MonitoringUnavailableException e) {
			log.warn("share 해소 전송 실패 — pending 유지 input={}", entry.input(), e);
			return;
		}

		long userId = registration.userId();
		if (!itemRepository.findActiveByInput(userId, MODE_URL, resolved.shortCode()).isEmpty()) {
			registrationRepository.updateEntryResult(entry.registrationId(), entry.seq(), RegistrationResult.DUPLICATE,
					RegistrationResult.REASON_DUPLICATE, DUPLICATE_MESSAGE, null, null);
			return;
		}

		Integer trackingDays = registration.trackingDays();
		if (trackingDays == null) {
			// V17 이전(마이그레이션 이전) 등록 잔재 방어 — 정상 플로우에선 등록 접수 시점에 항상 채워진다.
			log.error("registration에 trackingDays 없음 — share 처리 불가 registrationId={}", registration.id());
			registrationRepository.updateEntryResult(entry.registrationId(), entry.seq(), RegistrationResult.FAILED,
					RegistrationResult.REASON_INTERNAL_ERROR, "등록 정보 결손(trackingDays 없음)", null, null);
			return;
		}
		LocalDate registeredOn = KstTimestamps.toKstDate(registration.requestedAt());
		String canonicalUrl = canonicalUrl(resolved);

		UUID itemKey = UUID.randomUUID();
		long itemId = itemRepository.insertPending(userId, MODE_URL, itemKey, registration.campaignId(),
				resolved.shortCode(), canonicalUrl, null, trackingDays, registeredOn);
		// item_id를 여기서 즉시 연결해 둔다(아직 result는 pending 그대로) — register 호출이
		// MonitoringUnavailableException으로 끝나면 아래 catch에서 entry를 건드리지 않는데, 이때도
		// entries.item_id가 비어 있으면 recoverStalePending()이 나중에 성공시켜도 findEntryByItemId가
		// 못 찾아 entry·registration이 영영 pending으로 남는다(item_id를 나중에 걸면 이미 늦다).
		registrationRepository.updateEntryResult(entry.registrationId(), entry.seq(), RegistrationResult.PENDING,
				null, null, canonicalUrl, itemId);

		OffsetDateTime expiresAt = MonitoringExpiry.computeExpiresAt(registeredOn, trackingDays);
		try {
			RegisterResult result = registerWithRetry(RegisterRequest.post(itemKey, userId, resolved.shortCode(),
					expiresAt));
			itemRepository.confirmTarget(itemId, result.targetId());
			registrationRepository.updateEntryResult(entry.registrationId(), entry.seq(), RegistrationResult.SUCCESS,
					null, null, canonicalUrl, itemId);
		} catch (MonitoringApiException e) {
			itemRepository.delete(itemId);
			registrationRepository.updateEntryResult(entry.registrationId(), entry.seq(), RegistrationResult.FAILED,
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
				registrationRepository.updateEntryResult(entry.registrationId(), entry.seq(), RegistrationResult.SUCCESS,
						null, null, entry.resolvedUrl(), item.id());
				registrationRepository.markCompletedIfAllSettled(entry.registrationId());
			});
		} catch (MonitoringApiException e) {
			itemRepository.delete(item.id());
			entryOpt.ifPresent(entry -> {
				registrationRepository.updateEntryResult(entry.registrationId(), entry.seq(), RegistrationResult.FAILED,
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

	private ShareResolveResult resolveWithRetry(String url, long userId) {
		try {
			return client.resolveShare(url, userId);
		} catch (MonitoringUnavailableException first) {
			return client.resolveShare(url, userId);
		}
	}

	/** 동기 구간(V1MonitoringRegistrationService.writeKeywordsJson)이 저장한 {and,or,exclude} 형태를
	 * 계약 KeywordRule(and,any,exclude)로 옮긴다 — 저장 키는 "or"지만 계약·모니터링 어휘는 "any"다. */
	private KeywordRule readKeywordRule(String keywordsJson) {
		// keywords가 NULL(또는 blank)인 레거시 개인 추적 아이템 방어 — 빈 규칙으로 취급
		// (TrackingItemAssembler.readKeywordRule과 동일 규약, PR #521).
		if (keywordsJson == null || keywordsJson.isBlank()) {
			return new KeywordRule(List.of(), List.of(), List.of());
		}
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
			case "SUBJECT_NOT_FOUND" -> RegistrationResult.REASON_NOT_FOUND;
			case "PRIVATE_ACCOUNT" -> RegistrationResult.REASON_PRIVATE_ACCOUNT;
			case "VALIDATION" -> RegistrationResult.REASON_INVALID_FORMAT;
			default -> RegistrationResult.REASON_INTERNAL_ERROR;
		};
	}

	private static String mapShareReasonCode(String monitoringCode) {
		return switch (monitoringCode) {
			case "SHARE_LINK_UNRESOLVED" -> RegistrationResult.REASON_SHARE_LINK_UNRESOLVED;
			case "SUBJECT_NOT_FOUND" -> RegistrationResult.REASON_NOT_FOUND;
			default -> RegistrationResult.REASON_INTERNAL_ERROR;
		};
	}
}
