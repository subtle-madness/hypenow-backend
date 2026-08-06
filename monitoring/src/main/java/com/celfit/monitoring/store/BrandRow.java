package com.celfit.monitoring.store;

import com.celfit.monitoring.domain.BrandStatus;
import java.time.LocalDate;

/** 브랜드 스윕·등록이 쓰는 조회 단면 — followers·biography는 등록 시 1회 관측이라 싣지 않는다. */
public record BrandRow(long id, String username, String igUserId, BrandStatus status,
		LocalDate lastTrackedOn) {}
