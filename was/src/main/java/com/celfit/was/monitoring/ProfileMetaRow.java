package com.celfit.was.monitoring;

import java.time.LocalDate;

/** profile_meta 1행(계약 §3, v1.1) — 계정 단위 최신 1행. POST 등록만 있는 계정은 행이 없을 수 있다. */
public record ProfileMetaRow(String username, String displayName, String profileImageUrl,
		LocalDate lastUploadedAt) {
}
