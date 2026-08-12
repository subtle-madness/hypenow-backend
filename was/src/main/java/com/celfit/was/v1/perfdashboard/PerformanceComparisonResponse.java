package com.celfit.was.v1.perfdashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 성과 비교 집계 응답(스펙 2026-08-10) — 브랜드 계정 × 5구간(업로드일 기준, KST).
 * 참여율은 계산하지 않는다 — followersSum(분모)만 내리고 FE가 (likes+comments)÷followersSum으로
 * 계산한다(비율을 미리 내리면 구간·계정 합산 시 재평균이 불가능해진다 — FE 규칙 ②).
 *
 * <p>nullable 필드는 키를 생략하지 않고 명시적 null(계약 무결성 #1). 집계 합은 non-null 값의
 * 합이고, non-null이 하나도 없으면(0건 포함) null이다 — 합 0(전부 관측됐는데 0)과 null(전부
 * 미제공)을 FE가 다르게 표시한다(FE 규칙 ③ — 피드는 views가 항상 null).
 */
public record PerformanceComparisonResponse(List<AccountComparison> accounts) {

	/**
	 * 브랜드 계정 1개의 비교 축 — collectionStartedAt은 brand_account.registered_at(KST ISO).
	 * accountType은 구독 속성이다(own/competitor, 08-12) — 이 응답은 둘 다 포함한다(나란히 비교가
	 * 이 화면의 존재 이유라 accountType 필터가 없다, 스펙 §6).
	 */
	public record AccountComparison(String brandAccountId, String username,
			@Schema(allowableValues = {"own", "competitor"}) String accountType,
			String collectionStartedAt, List<Bucket> buckets) {
	}

	/**
	 * 구간 1개 집계. covered는 계정 단위 판정이다 — <b>최초 백필을 완주</b>(backfillCompletedAt
	 * 존재)했으면 5구간 전부 true: 백필이 등록 윈도우 365일 전체를 열거하므로 등록 시점과
	 * 무관하다(스펙 §covered — 등록일 기준이 아니다). 완주 기준인 이유(08-12): last_swept_at은
	 * 서빙 창(30일)만 커버해도 미리 찍혀 365일 완주를 보장하지 않는다. false는 "아직 365일 전량
	 * 수집 전"이라는 뜻이고 집계값은 그대로 내린다(direct 콘텐츠는 레거시 파이프라인이라 스윕
	 * 전에도 존재할 수 있다).
	 */
	public record Bucket(
			@Schema(allowableValues = {"1w", "1w_1m", "1m_3m", "3m_6m", "6m_12m"}) String key,
			boolean covered,
			int contentCount,
			Long views,
			Long likes,
			Long comments,
			Long followersSum,
			int viewsMissingCount,
			int likesHiddenCount,
			int followersMissingCount) {
	}
}
