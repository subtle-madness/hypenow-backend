package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandPostCampaignRepository;
import com.celfit.was.monitoring.BrandPostRegistrationEntryRow;
import com.celfit.was.monitoring.BrandPostRegistrationRepository;
import com.celfit.was.monitoring.BrandPostRegistrationRow;
import com.celfit.was.monitoring.MonitoringApiException;
import com.celfit.was.monitoring.MonitoringCommandClient;
import com.celfit.was.monitoring.MonitoringUnavailableException;
import com.celfit.was.monitoring.RegistrationResult;
import com.celfit.was.monitoring.ShareResolveResult;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 브랜드 direct 등록 접수(§T9) 커밋 이후 pending entry를 처리하는 백그라운드 실행기(2026-08-18 direct
 * 통합 §2-2-2). 구조는 {@code com.celfit.was.v1.monitoring.MonitoringRegistrationExecutor}를
 * 참고했다(복사가 아니라 관용구 승계) — 전용 스레드 풀, {@code afterCommit} 제출,
 * {@link RejectedExecutionException}은 로그만 남기고 pending 유지, 5분 stale 복구. 레거시와 달리
 * "확정 대상 아이템 테이블"이 없다 — entry 자체가 상태의 유일한 산지라 item 조회·target 확정 같은
 * 중간 단계가 없다.
 *
 * <h2>entry 1건 처리(설계 §2-2)</h2>
 * <ol>
 *   <li>shortCode 확정 — Post 입력은 접수 시점에 이미 entry.short_code에 있다. share 단축 링크
 *       (short_code NULL)만 여기서 {@code POST /api/share/resolve}로 해소한다.</li>
 *   <li>등록자 원장 재검사(공동 등록 허용, 08-19 개정) — 접수와 처리 사이의 경합(동시 등록 등)으로
 *       그새 <b>이 유저</b>가 같은 shortcode를 이미 등록했으면(app.brand_direct_posts) duplicate로
 *       확정한다. 브랜드 단위 direct_registered_at 재검사가 아니다 — 그건 "누군가" 등록했다는
 *       뜻일 뿐이라 다른 유저의 동시 등록을 duplicate로 오판하면 공동 등록이 성립하지 않는다.
 *       접수 시점의 표시 창 판정(§T9 register)은 이미 끝났으므로 여기서는 "그새 내가 등록했는가"만
 *       다시 본다 — 창 판정을 통째로 재계산하지 않는다.</li>
 *   <li>{@code POST /api/brands/{brandId}/direct-posts} 호출 — 성공(201·200)이면 success, 확정 실패
 *       ({@link MonitoringApiException})면 failed, 전송 실패({@link MonitoringUnavailableException})면
 *       pending 유지(stale 복구가 재시도).</li>
 *   <li>성공 시 {@code app.brand_direct_posts} 원장 upsert + campaignId가 있으면
 *       {@code app.brand_post_campaigns} 링크.</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class BrandDirectRegistrationExecutor {

	private static final Logger log = LoggerFactory.getLogger(BrandDirectRegistrationExecutor.class);

	/** 접수 커밋 직후 프로세스가 죽어(afterCommit 트리거 유실) 방치된 pending 복구 임계값. */
	private static final Duration STALE_AGE = Duration.ofMinutes(5);

	private static final String DUPLICATE_MESSAGE = "이미 브랜드 목록에 있는 게시물입니다.";

	private final MonitoringCommandClient client;
	private final BrandPostRegistrationRepository registrationRepository;
	private final BrandDirectPostRepository directPostRepository;
	private final BrandPostCampaignRepository campaignRepository;
	private final TaskExecutor executorPool;

	public BrandDirectRegistrationExecutor(MonitoringCommandClient client,
			BrandPostRegistrationRepository registrationRepository, BrandDirectPostRepository directPostRepository,
			BrandPostCampaignRepository campaignRepository,
			@Qualifier("brandDirectRegistrationTaskExecutor") TaskExecutor executorPool) {
		this.client = client;
		this.registrationRepository = registrationRepository;
		this.directPostRepository = directPostRepository;
		this.campaignRepository = campaignRepository;
		this.executorPool = executorPool;
	}

	/** registrationId에 속한 pending entry 처리를 백그라운드로 트리거한다(트랜잭션 계약은 RegistrationExecutor와 동일 — 커밋 후 호출). */
	public void submit(long registrationId) {
		try {
			executorPool.execute(() -> processSafely(registrationId));
		} catch (RejectedExecutionException e) {
			log.warn("브랜드 direct 등록 실행기 큐 초과 — 접수는 유지, pending 상태로 recoverStalePending 대기 registrationId={}",
					registrationId, e);
		}
	}

	/**
	 * 크래시 복구 — pending entry가 5분 넘게 방치된 등록을 다시 처리한다. monitoring 명령은 멱등이라
	 * 재시도 안전.
	 *
	 * <p><b>취소-복구 경합(2026-08-18 스테이징 실측)</b>: 등록 응답 유실로 pending에 머문 entry를
	 * 사용자가 그새 취소하면, 이 복구가 그 entry를 재시도해 이미 취소된 게시물을 재등록해 버리는
	 * 경합이 있었다 — {@code V1BrandDirectPostService#cancel}이 취소 시점에 같은 (brandId, shortCode)
	 * pending entry를 success로 정산하도록 고쳐 구조적으로 해소했다({@code settlePendingAsSuccessForCancel}
	 * 참조): 정산된 entry는 result가 pending이 아니게 되어 아래 조회 자체에 잡히지 않는다.
	 */
	public void recoverStalePending() {
		for (long registrationId : registrationRepository.findRegistrationIdsWithPendingOlderThan(STALE_AGE)) {
			try {
				process(registrationId);
			} catch (RuntimeException e) {
				log.error("브랜드 direct 등록 복구 실패 registrationId={}", registrationId, e);
			}
		}
	}

	/**
	 * 나이 기반 entry 확정(설계 R7 — 레거시 {@code settleStaleRegistrationEntries} 동형) — age(기본
	 * 24시간)보다 오래 pending인 entry를 failed로 못 박는다. 반드시 {@link #recoverStalePending()}
	 * 다음에 호출해야 한다(호출부: {@code BrandDirectRegistrationRecoveryScheduler}) — 역순이면 아직
	 * 복구로 살릴 수 있는 pending entry를 이 스윕이 먼저 failed로 확정해 버릴 수 있다.
	 */
	public void settleStaleRegistrationEntries(Duration age) {
		List<Long> registrationIds = registrationRepository.settleStalePendingEntries(age);
		for (Long registrationId : registrationIds) {
			registrationRepository.markCompletedIfAllSettled(registrationId);
		}
	}

	private void processSafely(long registrationId) {
		try {
			process(registrationId);
		} catch (RuntimeException e) {
			log.error("브랜드 direct 등록 백그라운드 처리 실패 registrationId={}", registrationId, e);
		}
	}

	private void process(long registrationId) {
		Optional<BrandPostRegistrationRow> registrationOpt = registrationRepository.findById(registrationId);
		if (registrationOpt.isEmpty()) {
			log.warn("실행기 대상 브랜드 direct 등록 없음 id={}", registrationId);
			return;
		}
		BrandPostRegistrationRow registration = registrationOpt.get();
		for (BrandPostRegistrationEntryRow entry : registration.entries()) {
			if (!RegistrationResult.PENDING.equals(entry.result())) {
				continue;
			}
			try {
				processEntry(registration, entry);
			} catch (RuntimeException e) {
				// entry 하나의 예외가 나머지 entry 처리·완료 마킹까지 끌고 내려가지 않게 격리한다.
				log.error("브랜드 direct entry 처리 중 예외 — pending 유지(재시도 여지) registrationId={} seq={}",
						registration.id(), entry.seq(), e);
			}
		}
		registrationRepository.markCompletedIfAllSettled(registrationId);
	}

	private void processEntry(BrandPostRegistrationRow registration, BrandPostRegistrationEntryRow entry) {
		String shortCode = entry.shortCode();
		if (shortCode == null) {
			shortCode = resolveShareLink(registration, entry);
			if (shortCode == null) {
				return;   // failed로 정산됐거나(확정 실패) pending 유지(전송 실패) — 둘 다 더 할 일 없음
			}
		}
		if (isAlreadyDirectRegisteredByUser(registration.brandId(), shortCode, registration.userId())) {
			settle(registration.id(), entry.seq(), shortCode, RegistrationResult.DUPLICATE,
					RegistrationResult.REASON_DUPLICATE, DUPLICATE_MESSAGE);
			return;
		}
		registerPost(registration, entry, shortCode);
	}

	/** @return 해소된 shortCode, 또는 null(failed로 정산됐거나 전송 실패로 pending 유지) */
	private String resolveShareLink(BrandPostRegistrationRow registration, BrandPostRegistrationEntryRow entry) {
		ShareResolveResult resolved;
		try {
			resolved = client.resolveShare(entry.input(), registration.userId());
		} catch (MonitoringApiException e) {
			settle(registration.id(), entry.seq(), null, RegistrationResult.FAILED, mapShareReasonCode(e.code()),
					e.getMessage());
			return null;
		} catch (MonitoringUnavailableException e) {
			log.warn("share 해소 전송 실패 — pending 유지 registrationId={} seq={}", registration.id(), entry.seq(), e);
			return null;
		}
		return resolved.shortCode();
	}

	/**
	 * 등록자 본인 재검사(공동 등록 허용, 08-19) — app.brand_direct_posts 원장 기준. 브랜드 단위
	 * direct_registered_at이 아니라 "이 유저"가 이미 등록했는지만 본다 — 다른 유저의 동시 등록은
	 * duplicate가 아니라 각자 독립적으로 성공해야 한다.
	 */
	private boolean isAlreadyDirectRegisteredByUser(long brandId, String shortCode, long userId) {
		return directPostRepository.findByUserAndShortCode(userId, shortCode)
				.filter(row -> row.brandId() == brandId)
				.isPresent();
	}

	private void registerPost(BrandPostRegistrationRow registration, BrandPostRegistrationEntryRow entry,
			String shortCode) {
		try {
			client.registerDirectPost(registration.brandId(), shortCode, null, false);
		} catch (MonitoringApiException e) {
			settle(registration.id(), entry.seq(), shortCode, RegistrationResult.FAILED, mapReasonCode(e.code()),
					e.getMessage());
			return;
		} catch (MonitoringUnavailableException e) {
			log.warn("direct 등록 전송 실패 — pending 유지(복구 대상) registrationId={} seq={} shortCode={}",
					registration.id(), entry.seq(), shortCode, e);
			return;
		}
		directPostRepository.upsertDirect(registration.userId(), registration.brandId(), shortCode);
		if (registration.campaignId() != null) {
			campaignRepository.upsert(registration.brandId(), shortCode, registration.campaignId(),
					registration.userId());
		}
		settle(registration.id(), entry.seq(), shortCode, RegistrationResult.SUCCESS, null, null);
	}

	private void settle(long registrationId, int seq, String shortCode, String result, String reasonCode,
			String reason) {
		registrationRepository.updateEntryResult(registrationId, seq, shortCode, result, reasonCode, reason);
	}

	/** monitoring direct 등록 명령의 에러 코드 → 레거시 reasonCode 어휘(§T8 표) — 어휘를 새로 만들지 않는다. */
	private static String mapReasonCode(String monitoringCode) {
		return switch (monitoringCode) {
			case "POST_NOT_FOUND" -> RegistrationResult.REASON_NOT_FOUND;
			case "PRIVATE_ACCOUNT" -> RegistrationResult.REASON_PRIVATE_ACCOUNT;
			default -> RegistrationResult.REASON_INTERNAL_ERROR;   // POST_UNSUPPORTED·BRAND_NOT_FOUND 등
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
