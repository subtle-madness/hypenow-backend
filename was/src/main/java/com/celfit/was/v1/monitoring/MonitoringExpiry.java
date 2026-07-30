package com.celfit.was.v1.monitoring;

import com.celfit.was.v1.common.KstTimestamps;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * target 만료 시각 계산 공식(유도표: registered_on + tracking_days일의 KST 자정, exclusive) —
 * 등록 실행기(첫 등록·복구 replay)와 6.29 기간 연장이 공용으로 쓴다(공식 중복 금지, 계획 문서
 * "computeExpiresAt 공용화 검토" 반영).
 */
public final class MonitoringExpiry {

	private MonitoringExpiry() {
	}

	public static OffsetDateTime computeExpiresAt(LocalDate registeredOn, int trackingDays) {
		return registeredOn.plusDays(trackingDays).atStartOfDay(KstTimestamps.KST).toOffsetDateTime();
	}
}
