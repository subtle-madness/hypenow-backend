package com.celfit.was.saved;

import java.time.OffsetDateTime;

/**
 * app.saved_influencers 1행 — handle은 accounts.handle 논리 참조일 뿐 FK가 아니다(§4-4, 조인 금지).
 * status 어휘는 was가 확정: reviewing(검토중)·contact_planned(컨택 예정)·collaborating(협업 중).
 */
public record SavedInfluencer(String handle, String status, String memo,
		OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
