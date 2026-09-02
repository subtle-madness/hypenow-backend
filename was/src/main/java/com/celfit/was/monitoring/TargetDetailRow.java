package com.celfit.was.monitoring;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * target 1행 + 표시 부속(post_meta·profile_meta·최신 팔로워·last sweep)을 한 SQL 왕복에 담은 통합
 * 행({@link MonitoringReadRepository#findTargetDetails}) — 6.26 목록 조립이 monitoring-ro 풀(3개)을
 * 왕복 5회 대신 1회만 점유하게 하는 것이 목적(2026-08-27 풀 대기 진단의 수리).
 *
 * <p>pm_*·pf_* 접두 컬럼은 LEFT JOIN 산물이라 행 부재 시 전부 null — 부재 판정 마커는 각각
 * {@code pmShortCode}·{@code pfUsername}(post_meta.short_code·profile_meta.username은 PK라 행이
 * 있으면 절대 null이 아니다). {@code lastCompletedAt}은 CROSS JOIN 스칼라라 모든 행에 같은 값.
 */
public record TargetDetailRow(long id, String type, String username, String shortCode,
		String keywordRule, String status, String trackedShortCode, OffsetDateTime trackedSince,
		String registrationKey, OffsetDateTime expiresAt, OffsetDateTime registeredAt,
		OffsetDateTime closedAt, OffsetDateTime lastFetchedAt, String failReason,
		Long userId, OffsetDateTime trackedHiddenAt, boolean fetchFailing, String matchedKeywords,
		String pmShortCode, String pmUsername, String pmContentType, LocalDate pmUploadedAt,
		String pmCaption, String pmThumbnailUrl, String pmImageObjectPath,
		String pfUsername, String pfDisplayName, String pfProfileImageUrl,
		LocalDate pfLastUploadedAt, String pfImageObjectPath,
		Long followers, OffsetDateTime lastCompletedAt) {

	/** target 부분만 — 기존 조립 로직(ItemStatus.derive 등)이 TargetRow를 그대로 소비한다. */
	public TargetRow toTargetRow() {
		return new TargetRow(id, type, username, shortCode, keywordRule, status, trackedShortCode,
				trackedSince, registrationKey, expiresAt, registeredAt, closedAt, lastFetchedAt,
				failReason, userId, trackedHiddenAt, fetchFailing, matchedKeywords);
	}

	/** post_meta 부분 — 행 부재(pmShortCode null)면 null(기존 findPostMeta 미포함과 동일 의미). */
	public PostMetaRow toPostMetaRow() {
		if (pmShortCode == null) {
			return null;
		}
		return new PostMetaRow(pmShortCode, pmUsername, pmContentType, pmUploadedAt, pmCaption,
				pmThumbnailUrl, pmImageObjectPath);
	}

	/** profile_meta 부분 — 행 부재(pfUsername null)면 null(기존 findProfileMeta 미포함과 동일 의미). */
	public ProfileMetaRow toProfileMetaRow() {
		if (pfUsername == null) {
			return null;
		}
		return new ProfileMetaRow(pfUsername, pfDisplayName, pfProfileImageUrl, pfLastUploadedAt,
				pfImageObjectPath);
	}
}
