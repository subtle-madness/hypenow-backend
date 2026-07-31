package com.celfit.was.monitoring;

import java.time.LocalDate;

/**
 * profile_meta 1행(계약 §3, v1.1) — 계정 단위 최신 1행. POST 등록만 있는 계정은 행이 없을 수 있다.
 * imageObjectPath는 monitoring이 자체 아카이브한 오브젝트 스토리지 경로(설계 스펙 §3-1) — null이면
 * 아직 아카이브되지 않았다는 뜻이라 서빙 측이 원본 CDN URL로 폴백한다.
 */
public record ProfileMetaRow(String username, String displayName, String profileImageUrl,
		LocalDate lastUploadedAt, String imageObjectPath) {
}
