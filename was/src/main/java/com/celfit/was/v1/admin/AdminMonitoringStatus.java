package com.celfit.was.v1.admin;

import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.monitoring.TargetRow;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 어드민 모니터링 행 상태 유도(설계 2026-08-01 §4 AdminMonitoringHealthService, §0 A6) — DB 접근
 * 없는 순수 함수만 모아 {@code ItemStatus}(유저 표면 유도)와 같은 방식으로 단위 테스트한다.
 *
 * <p>유저 표면 {@code ItemStatus}와 다른 점 2가지(설계서에 명시된 의도적 이탈):
 * <ol>
 *   <li>TRACKING 진행 중 재전이 우선순위가 반대다 — 유저 표면은 hidden이 error보다 우선하지만,
 *       어드민 계약은 error를 hidden_or_deleted보다 먼저 본다(요청서 표 순서).</li>
 *   <li>detect_stalled·collect_stalled라는 신규 "정체" 상태가 있다 — 유저 표면엔 없는, 운영자가
 *       개입해야 할 신호(감지 지연·수집 중단)를 드러내는 게 어드민 화면의 목적이라서다.</li>
 * </ol>
 *
 * <p>행 하나는 pending(target 미확정) 아니면 WATCHING 아니면 TRACKING 중 정확히 하나의 갈래로만
 * 떨어진다(§4 상태 머신과 동일한 배타성) — 그래서 "우선순위"는 사실 그 갈래 안에서의 우선순위일
 * 뿐, 갈래를 넘나드는 비교(예: detect_stalled vs error)는 발생하지 않는다.
 */
public final class AdminMonitoringStatus {

	public static final String ERROR = "error";
	public static final String HIDDEN_OR_DELETED = "hidden_or_deleted";
	public static final String DETECT_STALLED = "detect_stalled";
	public static final String COLLECT_STALLED = "collect_stalled";
	public static final String HEALTHY = "healthy";

	/** GET /v1/admin/monitoring/registrations?status= 검증(§4)·counts 5키 고정 순서(심각도순, §4 정렬). */
	public static final List<String> STATUS_ORDER =
			List.of(ERROR, HIDDEN_OR_DELETED, DETECT_STALLED, COLLECT_STALLED, HEALTHY);

	/** rows 정렬(§4) — 심각도 → lastCollectedAt 오래된 순(null이 가장 앞). */
	public static final Comparator<AdminMonitoringRow> ROW_COMPARATOR = Comparator
			.<AdminMonitoringRow>comparingInt(r -> STATUS_ORDER.indexOf(r.status()))
			.thenComparing(AdminMonitoringRow::lastCollectedAt, Comparator.nullsFirst(Comparator.naturalOrder()));

	private static final String MODE_ACCOUNT = "account";
	private static final String MODE_URL = "url";
	private static final String TARGET_WATCHING = "WATCHING";
	private static final String TARGET_TRACKING = "TRACKING";

	private static final long DETECT_STALL_DAYS = 7;   // A6
	private static final long COLLECT_STALL_HOURS = 48;   // A6

	private AdminMonitoringStatus() {
	}

	/**
	 * 활성 행 판정(§4) — canceled_at은 호출부가 SQL로 이미 걸렀다는 전제(findAllNotCanceled)라
	 * 여기서는 target 미확정 pending의 기간 만료·target 상태만 본다. target 조회 실패/부재 방어는
	 * pending과 동일 취급(target==null이면 targetId 유무와 무관하게 pending 분기로 떨어진다).
	 */
	public static boolean isActive(MonitoringItemRow item, TargetRow target, LocalDate today) {
		if (item.targetId() == null || target == null) {
			LocalDate endDate = item.registeredOn().plusDays(item.trackingDays());
			return endDate.isAfter(today);
		}
		return TARGET_WATCHING.equals(target.status()) || TARGET_TRACKING.equals(target.status());
	}

	/**
	 * 상태 유도(§4 우선순위) — 호출 전 {@link #isActive}가 true라는 전제. latestSnapshotDate는 해당
	 * 게시물의 최신 post_snapshot.captured_on(없으면 null), now는 collect_stalled의 tracked_since
	 * 48시간 비교에 쓴다.
	 */
	public static String deriveStatus(MonitoringItemRow item, TargetRow target, LocalDate today,
			OffsetDateTime now, LocalDate latestSnapshotDate) {
		if (item.targetId() == null || target == null) {
			return derivePendingStatus(item, today);
		}
		return switch (target.status()) {
			case TARGET_WATCHING -> deriveWatchingStatus(item, today);
			case TARGET_TRACKING -> deriveTrackingStatus(target, today, now, latestSnapshotDate);
			// isActive가 걸렀어야 하는 갈래(EXPIRED/CANCELED/FAILED) — 방어적으로 healthy.
			default -> HEALTHY;
		};
	}

	/** target 미확정 — account 모드만 detect_stalled 대상(url pending은 아직 정의된 정체 상태가 없다). */
	private static String derivePendingStatus(MonitoringItemRow item, LocalDate today) {
		if (MODE_ACCOUNT.equals(item.mode()) && isDetectStalled(item.registeredOn(), today)) {
			return DETECT_STALLED;
		}
		return HEALTHY;
	}

	private static String deriveWatchingStatus(MonitoringItemRow item, LocalDate today) {
		return isDetectStalled(item.registeredOn(), today) ? DETECT_STALLED : HEALTHY;
	}

	private static boolean isDetectStalled(LocalDate registeredOn, LocalDate today) {
		return !today.isBefore(registeredOn.plusDays(DETECT_STALL_DAYS));
	}

	/** TRACKING — error > hidden_or_deleted > collect_stalled > healthy(요청서 표 순서, ItemStatus와 반대). */
	private static String deriveTrackingStatus(TargetRow target, LocalDate today, OffsetDateTime now,
			LocalDate latestSnapshotDate) {
		if (target.fetchFailing()) {
			return ERROR;
		}
		if (target.trackedHiddenAt() != null) {
			return HIDDEN_OR_DELETED;
		}
		if (isCollectStalled(target, today, now, latestSnapshotDate)) {
			return COLLECT_STALLED;
		}
		return HEALTHY;
	}

	private static boolean isCollectStalled(TargetRow target, LocalDate today, OffsetDateTime now,
			LocalDate latestSnapshotDate) {
		if (latestSnapshotDate != null) {
			// "일일 스윕 기준 2회 연속 미갱신" — 최신 캡처가 오늘-2일 이하면 정체.
			return !latestSnapshotDate.isAfter(today.minusDays(2));
		}
		// 스냅샷이 한 번도 없으면 추적 시작 시각(tracked_since) 기준 48시간.
		return target.trackedSince() != null && target.trackedSince().isBefore(now.minusHours(COLLECT_STALL_HOURS));
	}
}
