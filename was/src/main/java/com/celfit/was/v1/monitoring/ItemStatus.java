package com.celfit.was.v1.monitoring;

import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.monitoring.TargetRow;

/**
 * TrackingItem.status 유도(계약 6.25 상태 머신, v2.2 기준) — monitoring_items 행 + target 행(nullable)에서
 * 프론트 status 문자열을 계산한다. 6.29(기간 변경)·6.30(취소)의 "진행 중" 판정과 6.26 어셈블러가
 * 공용으로 쓴다.
 *
 * <p>규칙(계약 v2.2 기준 — 승인 폐지로 구 유도표의 "WATCHING+PENDING→collecting" 분기는 신규 도달
 * 불가, plans/2026-07-30-monitoring-v3-merge-gaps.md A-2-5 참조. P1 합류로 hidden·error 신호가
 * target 표면(tracked_hidden_at·fetch_failing)에 실재해 진행 중 상태에서의 재전이를 유도할 수 있다):
 * <ol>
 *   <li>canceled_at != null → canceled_from=detecting ? not_uploaded : ended</li>
 *   <li>target_id == null(등록 처리 중) → mode=url ? collecting : detecting</li>
 *   <li>target 조회 실패/부재(방어) → 2와 동일</li>
 *   <li>WATCHING → detecting</li>
 *   <li>TRACKING → tracked_hidden_at 있으면 hidden, 아니면 fetch_failing이면 error, 아니면 tracking</li>
 *   <li>EXPIRED → tracked_short_code null이면 not_uploaded, tracked_hidden_at 있으면 hidden(만료 후에도
 *       hidden 유지 — fetch_failing은 만료로 넘어가지 않는다, 계약 "기간이 만료되면 ended"), 아니면 ended</li>
 *   <li>CANCELED(방어 — 취소는 1이 잡음) → 6과 동일 규칙</li>
 *   <li>FAILED(방어 — 등록 실패는 행 삭제) → error</li>
 * </ol>
 */
public final class ItemStatus {

	public static final String COLLECTING = "collecting";
	public static final String DETECTING = "detecting";
	public static final String TRACKING = "tracking";
	public static final String NOT_UPLOADED = "not_uploaded";
	public static final String ENDED = "ended";
	public static final String HIDDEN = "hidden";
	public static final String ERROR = "error";

	private static final String MODE_URL = "url";
	private static final String CANCELED_FROM_DETECTING = "detecting";

	private static final String TARGET_WATCHING = "WATCHING";
	private static final String TARGET_TRACKING = "TRACKING";
	private static final String TARGET_EXPIRED = "EXPIRED";
	private static final String TARGET_CANCELED = "CANCELED";
	private static final String TARGET_FAILED = "FAILED";

	private ItemStatus() {
	}

	public static String derive(MonitoringItemRow item, TargetRow target) {
		if (item.canceledAt() != null) {
			return CANCELED_FROM_DETECTING.equals(item.canceledFrom()) ? NOT_UPLOADED : ENDED;
		}
		if (item.targetId() == null || target == null) {
			return MODE_URL.equals(item.mode()) ? COLLECTING : DETECTING;
		}
		return switch (target.status()) {
			case TARGET_WATCHING -> DETECTING;
			case TARGET_TRACKING -> deriveTracking(target);
			case TARGET_EXPIRED, TARGET_CANCELED -> deriveClosed(target);
			case TARGET_FAILED -> ERROR;
			default -> throw new IllegalStateException("알 수 없는 target.status: " + target.status());
		};
	}

	/** TRACKING 진행 중 재전이 — hidden이 error보다 우선(둘 다 세팅될 일은 없지만 감지 결정성 우위는 hidden). */
	private static String deriveTracking(TargetRow target) {
		if (target.trackedHiddenAt() != null) {
			return HIDDEN;
		}
		if (target.fetchFailing()) {
			return ERROR;
		}
		return TRACKING;
	}

	/** EXPIRED/CANCELED 종결 — tracked_hidden_at만 만료 후로 이어진다(fetch_failing은 이어지지 않음). */
	private static String deriveClosed(TargetRow target) {
		if (target.trackedShortCode() == null) {
			return NOT_UPLOADED;
		}
		return target.trackedHiddenAt() != null ? HIDDEN : ENDED;
	}

	/** 계약 6.25 라벨(참고) — 400 안내 문구("현재 상태: …")에 쓴다. */
	public static String label(String status) {
		return switch (status) {
			case COLLECTING -> "게시물 확인 중";
			case DETECTING -> "업로드 감지 중";
			case TRACKING -> "모니터링 중";
			case NOT_UPLOADED -> "미업로드";
			case ENDED -> "종료";
			case HIDDEN -> "숨김";
			case ERROR -> "오류";
			default -> status;
		};
	}
}
