package com.celfit.was.v1.monitoring;

import com.celfit.was.v1.common.KstTimestamps;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

/**
 * target 만료·다음 스윕 시각 계산 공식 — 등록 실행기(첫 등록·복구 replay)·6.29 기간 연장·
 * 6.26 어셈블러(detecting의 nextCheckAt)가 공용으로 쓴다(공식 중복 금지, 계획 문서
 * "computeExpiresAt 공용화 검토" 반영 — nextSweepAt은 V1MonitoringItemUpdateService의 private
 * 헬퍼였던 것을 어셈블러 도입 시 여기로 옮겼다).
 */
public final class MonitoringExpiry {

	private MonitoringExpiry() {
	}

	/** 유도표: registered_on + tracking_days일의 KST 자정(exclusive). */
	public static OffsetDateTime computeExpiresAt(LocalDate registeredOn, int trackingDays) {
		return registeredOn.plusDays(trackingDays).atStartOfDay(KstTimestamps.KST).toOffsetDateTime();
	}

	/** 다음 일일 스윕(KST 02:00) 예정 시각 — detecting 상태의 nextCheckAt 근사값(계약 6.25). */
	public static OffsetDateTime nextSweepAt() {
		ZonedDateTime now = ZonedDateTime.now(KstTimestamps.KST);
		ZonedDateTime today2am = now.toLocalDate().atTime(2, 0).atZone(KstTimestamps.KST);
		ZonedDateTime next = now.isBefore(today2am) ? today2am : today2am.plusDays(1);
		return next.toOffsetDateTime();
	}
}
