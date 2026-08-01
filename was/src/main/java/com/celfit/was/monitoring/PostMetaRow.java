package com.celfit.was.monitoring;

import java.time.LocalDate;

/**
 * post_meta 1행(계약 §3, v2.2) — 게시물 단위 최신 1행. caption은 DB NOT NULL(빈 문자열 허용).
 * imageObjectPath는 monitoring이 자체 아카이브한 게시물 썸네일 오브젝트 스토리지 경로(트랙 KK 확장,
 * profile_meta.image_object_path와 동형) — null이면 아직 아카이브되지 않았다는 뜻이라 서빙 측이
 * 원본 CDN URL로 폴백한다.
 */
public record PostMetaRow(String shortCode, String username, String contentType, LocalDate uploadedAt,
		String caption, String thumbnailUrl, String imageObjectPath) {
}
