package com.celfit.was.monitoring;

import java.time.LocalDate;

/** post_meta 1행(계약 §3, v2.2) — 게시물 단위 최신 1행. caption은 DB NOT NULL(빈 문자열 허용). */
public record PostMetaRow(String shortCode, String username, String contentType, LocalDate uploadedAt,
		String caption, String thumbnailUrl) {
}
