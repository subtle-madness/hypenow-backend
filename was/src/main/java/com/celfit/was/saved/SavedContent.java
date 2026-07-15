package com.celfit.was.saved;

import java.time.OffsetDateTime;

/** app.saved_contents 1행 — short_code는 contents.short_code 논리 참조일 뿐 FK가 아니다(§4-4). */
public record SavedContent(String shortCode, OffsetDateTime createdAt) {
}
