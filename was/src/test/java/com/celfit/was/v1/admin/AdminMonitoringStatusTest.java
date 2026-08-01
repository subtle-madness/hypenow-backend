package com.celfit.was.v1.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.monitoring.MonitoringItemRow;
import com.celfit.was.monitoring.TargetRow;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 어드민 모니터링 상태 유도 순수 함수 단위 테스트(설계 2026-08-01 §4·§0 A6) — DB 없이 우선순위·
 * 7일/48시간 경계·pending 만료 제외·monitoring 부재(target=null) 폴백을 고정한다.
 */
class AdminMonitoringStatusTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 8, 1);
	private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 1, 12, 0, 0, 0, ZoneOffset.of("+09:00"));

	// --- isActive: pending(target 미확정) ---

	@Test
	void pending_기간_미만료면_활성이다() {
		MonitoringItemRow item = pendingItem("account", TODAY.minusDays(5), 14);
		assertThat(AdminMonitoringStatus.isActive(item, null, TODAY)).isTrue();
	}

	@Test
	void pending_기간_만료면_비활성이다() {
		MonitoringItemRow item = pendingItem("account", TODAY.minusDays(30), 14);
		assertThat(AdminMonitoringStatus.isActive(item, null, TODAY)).isFalse();
	}

	@Test
	void target_조회_실패_방어는_pending과_동일_취급된다() {
		// targetId는 있지만(호출부 맵에 없어 null 전달) — pending과 같은 만료 규칙을 탄다.
		MonitoringItemRow item = new MonitoringItemRow(1L, 7L, "account", UUID.randomUUID(), 900L, null,
				"glowdeep", null, "{}", 14, TODAY.minusDays(5), null, null, OffsetDateTime.now());
		assertThat(AdminMonitoringStatus.isActive(item, null, TODAY)).isTrue();

		MonitoringItemRow expired = new MonitoringItemRow(2L, 7L, "account", UUID.randomUUID(), 900L, null,
				"glowdeep", null, "{}", 14, TODAY.minusDays(30), null, null, OffsetDateTime.now());
		assertThat(AdminMonitoringStatus.isActive(expired, null, TODAY)).isFalse();
	}

	// --- isActive: target 확정 ---

	@Test
	void target_WATCHING_TRACKING은_활성이고_나머지는_비활성이다() {
		MonitoringItemRow item = confirmedItem("account", TODAY, 14);
		assertThat(AdminMonitoringStatus.isActive(item, target("WATCHING"), TODAY)).isTrue();
		assertThat(AdminMonitoringStatus.isActive(item, target("TRACKING"), TODAY)).isTrue();
		assertThat(AdminMonitoringStatus.isActive(item, target("EXPIRED"), TODAY)).isFalse();
		assertThat(AdminMonitoringStatus.isActive(item, target("CANCELED"), TODAY)).isFalse();
		assertThat(AdminMonitoringStatus.isActive(item, target("FAILED"), TODAY)).isFalse();
	}

	// --- deriveStatus 우선순위(TRACKING): error > hidden_or_deleted > collect_stalled > healthy ---

	@Test
	void TRACKING_fetchFailing이면_hidden도_같이_있어도_error가_우선한다() {
		TargetRow target = trackingTarget(true, NOW.minusHours(1), null);
		String status = AdminMonitoringStatus.deriveStatus(confirmedItem("account", TODAY, 14), target, TODAY, NOW, null);
		assertThat(status).isEqualTo(AdminMonitoringStatus.ERROR);
	}

	@Test
	void TRACKING_hidden만_있으면_hidden_or_deleted다() {
		TargetRow target = trackingTarget(false, NOW.minusHours(1), null);
		String status = AdminMonitoringStatus.deriveStatus(confirmedItem("account", TODAY, 14), target, TODAY, NOW, null);
		assertThat(status).isEqualTo(AdminMonitoringStatus.HIDDEN_OR_DELETED);
	}

	@Test
	void TRACKING_수집_정체_48시간_경계() {
		TargetRow target = trackingTarget(false, null, NOW.minusHours(49));
		assertThat(AdminMonitoringStatus.deriveStatus(confirmedItem("account", TODAY, 14), target, TODAY, NOW, null))
				.isEqualTo(AdminMonitoringStatus.COLLECT_STALLED);

		TargetRow healthy = trackingTarget(false, null, NOW.minusHours(47));
		assertThat(AdminMonitoringStatus.deriveStatus(confirmedItem("account", TODAY, 14), healthy, TODAY, NOW, null))
				.isEqualTo(AdminMonitoringStatus.HEALTHY);
	}

	@Test
	void TRACKING_최신_스냅샷_기준_수집_정체_2일_경계() {
		TargetRow target = trackingTarget(false, null, null);
		// 스냅샷이 오늘-2일이면 정체(2회 연속 미갱신), 오늘-1일이면 아직 정상.
		assertThat(AdminMonitoringStatus.deriveStatus(confirmedItem("account", TODAY, 14), target, TODAY, NOW,
				TODAY.minusDays(2))).isEqualTo(AdminMonitoringStatus.COLLECT_STALLED);
		assertThat(AdminMonitoringStatus.deriveStatus(confirmedItem("account", TODAY, 14), target, TODAY, NOW,
				TODAY.minusDays(1))).isEqualTo(AdminMonitoringStatus.HEALTHY);
	}

	@Test
	void TRACKING_정상이면_healthy다() {
		TargetRow target = trackingTarget(false, null, null);
		assertThat(AdminMonitoringStatus.deriveStatus(confirmedItem("account", TODAY, 14), target, TODAY, NOW,
				TODAY)).isEqualTo(AdminMonitoringStatus.HEALTHY);
	}

	// --- deriveStatus: WATCHING/pending의 detect_stalled(7일 경계) ---

	@Test
	void WATCHING_7일_경계() {
		MonitoringItemRow item7 = confirmedItem("account", TODAY.minusDays(7), 90);
		assertThat(AdminMonitoringStatus.deriveStatus(item7, target("WATCHING"), TODAY, NOW, null))
				.isEqualTo(AdminMonitoringStatus.DETECT_STALLED);

		MonitoringItemRow item6 = confirmedItem("account", TODAY.minusDays(6), 90);
		assertThat(AdminMonitoringStatus.deriveStatus(item6, target("WATCHING"), TODAY, NOW, null))
				.isEqualTo(AdminMonitoringStatus.HEALTHY);
	}

	@Test
	void account_pending은_7일_경과시_detect_stalled_url_pending은_healthy만_가능하다() {
		MonitoringItemRow accountPending = pendingItem("account", TODAY.minusDays(10), 90);
		assertThat(AdminMonitoringStatus.deriveStatus(accountPending, null, TODAY, NOW, null))
				.isEqualTo(AdminMonitoringStatus.DETECT_STALLED);

		MonitoringItemRow urlPending = pendingItem("url", TODAY.minusDays(10), 90);
		assertThat(AdminMonitoringStatus.deriveStatus(urlPending, null, TODAY, NOW, null))
				.isEqualTo(AdminMonitoringStatus.HEALTHY);
	}

	// --- 정렬 ---

	@Test
	void ROW_COMPARATOR는_심각도_다음_lastCollectedAt_오래된_순_null_최우선() {
		AdminMonitoringRow healthy1 = row(AdminMonitoringStatus.HEALTHY, OffsetDateTime.parse("2026-07-30T00:00:00+09:00"));
		AdminMonitoringRow healthyNull = row(AdminMonitoringStatus.HEALTHY, null);
		AdminMonitoringRow error = row(AdminMonitoringStatus.ERROR, OffsetDateTime.parse("2026-07-31T00:00:00+09:00"));
		AdminMonitoringRow hidden = row(AdminMonitoringStatus.HIDDEN_OR_DELETED, null);

		List<AdminMonitoringRow> sorted =
				List.of(healthy1, healthyNull, error, hidden).stream().sorted(AdminMonitoringStatus.ROW_COMPARATOR).toList();

		assertThat(sorted).containsExactly(error, hidden, healthyNull, healthy1);
	}

	// --- 픽스처 헬퍼 ---

	private static MonitoringItemRow pendingItem(String mode, LocalDate registeredOn, int trackingDays) {
		return new MonitoringItemRow(1L, 7L, mode, UUID.randomUUID(), null, null, "glowdeep",
				"url".equals(mode) ? "https://www.instagram.com/p/ABC123/" : null, "{}", trackingDays, registeredOn,
				null, null, OffsetDateTime.now());
	}

	/** target 확정 행(targetId=900L, target() 픽스처의 id와 짝) — isActive/deriveStatus의 "target 있음" 분기 전용. */
	private static MonitoringItemRow confirmedItem(String mode, LocalDate registeredOn, int trackingDays) {
		return new MonitoringItemRow(1L, 7L, mode, UUID.randomUUID(), 900L, null, "glowdeep",
				"url".equals(mode) ? "https://www.instagram.com/p/ABC123/" : null, "{}", trackingDays, registeredOn,
				null, null, OffsetDateTime.now());
	}

	private static TargetRow target(String status) {
		return new TargetRow(900L, "ACCOUNT", "glowdeep", null, null, status, null, null, "key",
				OffsetDateTime.now(), OffsetDateTime.now(), null, null, null, null, null, false, null);
	}

	private static TargetRow trackingTarget(boolean fetchFailing, OffsetDateTime trackedHiddenAt,
			OffsetDateTime trackedSince) {
		return new TargetRow(900L, "ACCOUNT", "glowdeep", null, null, "TRACKING", "SHORT1", trackedSince, "key",
				OffsetDateTime.now(), OffsetDateTime.now(), null, null, null, null, trackedHiddenAt, fetchFailing, null);
	}

	private static AdminMonitoringRow row(String status, OffsetDateTime lastCollectedAt) {
		return new AdminMonitoringRow(1L, 7L, null, status, "@glowdeep", "reels", lastCollectedAt, null);
	}
}
