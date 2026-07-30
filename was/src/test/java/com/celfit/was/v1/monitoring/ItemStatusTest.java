package com.celfit.was.v1.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.monitoring.TargetRow;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ItemStatus.derive 유도표 8행(계약 6.25·v2.1, 태스크 사양 그대로) + pending 만료 유도(v2.2 리뷰
 * 반영) 고정 테스트. 값 자체보다 "이 입력 조합이면 이 status"가 후속 6.26 어셈블러의 회귀 기준이
 * 되므로 각 행을 개별 테스트로 둔다.
 */
class ItemStatusTest {

	/** item() 기본 registeredOn(2026-07-01) + trackingDays(14) = 만료일 2026-07-15 — 이 날짜는 그 이전이라 미만료. */
	private static final LocalDate TODAY_BEFORE_EXPIRY = LocalDate.of(2026, 7, 1);

	private static MonitoringItemRow item(String mode, Long targetId, OffsetDateTime canceledAt,
			String canceledFrom) {
		return item(mode, targetId, canceledAt, canceledFrom, LocalDate.of(2026, 7, 1), 14);
	}

	private static MonitoringItemRow item(String mode, Long targetId, OffsetDateTime canceledAt,
			String canceledFrom, LocalDate registeredOn, int trackingDays) {
		return new MonitoringItemRow(1L, 7L, mode, UUID.randomUUID(), targetId, null, "input", null, null,
				trackingDays, registeredOn, canceledAt, canceledFrom, OffsetDateTime.now());
	}

	private static TargetRow target(String status, String trackedShortCode) {
		return target(status, trackedShortCode, null, false);
	}

	private static TargetRow target(String status, String trackedShortCode, OffsetDateTime trackedHiddenAt,
			boolean fetchFailing) {
		return new TargetRow(99L, "ACCOUNT", "some_influencer", null, null, status, trackedShortCode, null,
				"key", OffsetDateTime.now(), OffsetDateTime.now(), null, null, null, null, trackedHiddenAt,
				fetchFailing, null);
	}

	@Test
	void 규칙1_취소됨_detecting에서_취소면_not_uploaded() {
		MonitoringItemRow row = item("account", 5L, OffsetDateTime.now(), "detecting");

		assertThat(ItemStatus.derive(row, target("WATCHING", null), TODAY_BEFORE_EXPIRY)).isEqualTo(ItemStatus.NOT_UPLOADED);
	}

	@Test
	void 규칙1_취소됨_tracking에서_취소면_ended() {
		MonitoringItemRow row = item("url", 5L, OffsetDateTime.now(), "tracking");

		assertThat(ItemStatus.derive(row, target("TRACKING", "SHORT1"), TODAY_BEFORE_EXPIRY)).isEqualTo(ItemStatus.ENDED);
	}

	@Test
	void 규칙1_취소됨_error에서_취소면_ended() {
		MonitoringItemRow row = item("account", 5L, OffsetDateTime.now(), "error");

		assertThat(ItemStatus.derive(row, target("FAILED", null), TODAY_BEFORE_EXPIRY)).isEqualTo(ItemStatus.ENDED);
	}

	@Test
	void 규칙2_target_id_null_url_모드는_collecting() {
		MonitoringItemRow row = item("url", null, null, null);

		assertThat(ItemStatus.derive(row, null, TODAY_BEFORE_EXPIRY)).isEqualTo(ItemStatus.COLLECTING);
	}

	@Test
	void 규칙2_target_id_null_account_모드는_detecting() {
		MonitoringItemRow row = item("account", null, null, null);

		assertThat(ItemStatus.derive(row, null, TODAY_BEFORE_EXPIRY)).isEqualTo(ItemStatus.DETECTING);
	}

	@Test
	void 규칙3_target_id는_있는데_조회_결과가_없으면_모드_기준으로_방어() {
		MonitoringItemRow row = item("account", 5L, null, null);

		assertThat(ItemStatus.derive(row, null, TODAY_BEFORE_EXPIRY)).isEqualTo(ItemStatus.DETECTING);
	}

	@Test
	void 규칙4_WATCHING은_detecting() {
		MonitoringItemRow row = item("account", 5L, null, null);

		assertThat(ItemStatus.derive(row, target("WATCHING", null), TODAY_BEFORE_EXPIRY)).isEqualTo(ItemStatus.DETECTING);
	}

	@Test
	void 규칙5_TRACKING은_tracking() {
		MonitoringItemRow row = item("url", 5L, null, null);

		assertThat(ItemStatus.derive(row, target("TRACKING", "SHORT1"), TODAY_BEFORE_EXPIRY)).isEqualTo(ItemStatus.TRACKING);
	}

	@Test
	void 규칙6_EXPIRED_추적게시물_없으면_not_uploaded() {
		MonitoringItemRow row = item("account", 5L, null, null);

		assertThat(ItemStatus.derive(row, target("EXPIRED", null), TODAY_BEFORE_EXPIRY)).isEqualTo(ItemStatus.NOT_UPLOADED);
	}

	@Test
	void 규칙6_EXPIRED_추적게시물_있으면_ended() {
		MonitoringItemRow row = item("account", 5L, null, null);

		assertThat(ItemStatus.derive(row, target("EXPIRED", "SHORT1"), TODAY_BEFORE_EXPIRY)).isEqualTo(ItemStatus.ENDED);
	}

	@Test
	void 규칙7_CANCELED_방어_추적게시물_없으면_not_uploaded() {
		MonitoringItemRow row = item("account", 5L, null, null);

		assertThat(ItemStatus.derive(row, target("CANCELED", null), TODAY_BEFORE_EXPIRY)).isEqualTo(ItemStatus.NOT_UPLOADED);
	}

	@Test
	void 규칙7_CANCELED_방어_추적게시물_있으면_ended() {
		MonitoringItemRow row = item("url", 5L, null, null);

		assertThat(ItemStatus.derive(row, target("CANCELED", "SHORT1"), TODAY_BEFORE_EXPIRY)).isEqualTo(ItemStatus.ENDED);
	}

	@Test
	void 규칙8_FAILED_방어는_error() {
		MonitoringItemRow row = item("account", 5L, null, null);

		assertThat(ItemStatus.derive(row, target("FAILED", null), TODAY_BEFORE_EXPIRY)).isEqualTo(ItemStatus.ERROR);
	}

	// ── P1 합류(v2.2) — TRACKING 진행 중 재전이(hidden·error) + 만료 후 hidden 유지 ──────

	@Test
	void 규칙5_TRACKING_tracked_hidden_at_있으면_hidden() {
		MonitoringItemRow row = item("account", 5L, null, null);

		assertThat(ItemStatus.derive(row, target("TRACKING", "SHORT1", OffsetDateTime.now(), false), TODAY_BEFORE_EXPIRY))
				.isEqualTo(ItemStatus.HIDDEN);
	}

	@Test
	void 규칙5_TRACKING_fetch_failing이면_error() {
		MonitoringItemRow row = item("account", 5L, null, null);

		assertThat(ItemStatus.derive(row, target("TRACKING", "SHORT1", null, true), TODAY_BEFORE_EXPIRY))
				.isEqualTo(ItemStatus.ERROR);
	}

	@Test
	void 규칙5_TRACKING_hidden이_error보다_우선() {
		MonitoringItemRow row = item("account", 5L, null, null);

		assertThat(ItemStatus.derive(row, target("TRACKING", "SHORT1", OffsetDateTime.now(), true), TODAY_BEFORE_EXPIRY))
				.isEqualTo(ItemStatus.HIDDEN);
	}

	@Test
	void 규칙6_EXPIRED_tracked_hidden_at_있으면_만료_후에도_hidden_유지() {
		MonitoringItemRow row = item("account", 5L, null, null);

		assertThat(ItemStatus.derive(row, target("EXPIRED", "SHORT1", OffsetDateTime.now(), false), TODAY_BEFORE_EXPIRY))
				.isEqualTo(ItemStatus.HIDDEN);
	}

	@Test
	void 규칙6_EXPIRED_fetch_failing은_만료로_이어지지_않고_ended() {
		MonitoringItemRow row = item("account", 5L, null, null);

		assertThat(ItemStatus.derive(row, target("EXPIRED", "SHORT1", null, true), TODAY_BEFORE_EXPIRY))
				.isEqualTo(ItemStatus.ENDED);
	}

	// ── 리뷰 반영(2026-07-30) — target 미확정(pending) 행의 기간 만료 유도 ──────────────

	@Test
	void 규칙2_pending_url_모드_미만료면_기존대로_collecting() {
		MonitoringItemRow row = item("url", null, null, null, LocalDate.of(2026, 7, 1), 14);

		assertThat(ItemStatus.derive(row, null, LocalDate.of(2026, 7, 14))).isEqualTo(ItemStatus.COLLECTING);
	}

	@Test
	void 규칙2_pending_account_모드_미만료면_기존대로_detecting() {
		MonitoringItemRow row = item("account", null, null, null, LocalDate.of(2026, 7, 1), 14);

		assertThat(ItemStatus.derive(row, null, LocalDate.of(2026, 7, 14))).isEqualTo(ItemStatus.DETECTING);
	}

	@Test
	void 규칙2_pending_url_모드가_만료되면_ended_post는_없다() {
		// registeredOn(07-01) + trackingDays(14) = 07-15 자정(exclusive) — 그 날짜부터 만료.
		MonitoringItemRow row = item("url", null, null, null, LocalDate.of(2026, 7, 1), 14);

		assertThat(ItemStatus.derive(row, null, LocalDate.of(2026, 7, 15))).isEqualTo(ItemStatus.ENDED);
	}

	@Test
	void 규칙2_pending_account_모드가_만료되면_not_uploaded() {
		MonitoringItemRow row = item("account", null, null, null, LocalDate.of(2026, 7, 1), 14);

		assertThat(ItemStatus.derive(row, null, LocalDate.of(2026, 7, 15))).isEqualTo(ItemStatus.NOT_UPLOADED);
	}

	@Test
	void 규칙3_target_조회_실패_방어도_만료_판정을_받는다() {
		// target_id는 있지만(방어 경로) 조회 결과가 없는 경우도 2와 동일한 만료 판정을 탄다.
		MonitoringItemRow row = item("url", 5L, null, null, LocalDate.of(2026, 7, 1), 14);

		assertThat(ItemStatus.derive(row, null, LocalDate.of(2026, 7, 15))).isEqualTo(ItemStatus.ENDED);
	}
}
