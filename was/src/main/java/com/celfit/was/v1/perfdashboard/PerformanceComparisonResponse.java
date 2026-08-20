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
	 * 브랜드 계정 1개의 비교 축 — collectionStartedAt은 brand_account.collection_started_at
	 * (KST ISO, 기간 확장 시 갱신 — 브랜드 계정 API의 동명 필드와 같은 앵커. registered_at 아님).
	 * accountType은 구독 속성이다(own/competitor, 08-12) — 이 응답은 둘 다 포함한다(나란히 비교가
	 * 이 화면의 존재 이유라 accountType 필터가 없다, 스펙 §6).
	 */
	public record AccountComparison(String brandAccountId, String username,
			@Schema(allowableValues = {"own", "competitor"}) String accountType,
			String collectionStartedAt, List<Bucket> buckets) {
	}

	/**
	 * 구간 1개 집계. covered는 <b>버킷별</b> 판정이다(collectionMonths 스펙 2026-08-12,
	 * 실수집 깊이 반영 2026-08-19): 백필 완주(backfillCompletedAt 존재·확장 진행 중 아님)
	 * <b>그리고</b> 버킷 범위가 실제 수집 범위 안 — 먼 쪽 경계가 collection_months 창 하한
	 * <b>과 실수집 깊이(brand_account.covered_until, 수집 개수 상한 컷 시 monitoring이 영속화 —
	 * 수집 상한 v2 §7-1)</b> 중 얕은 쪽 이상(부분 겹침은 보수적으로 false)일 때만 true다.
	 *
	 * <p>따라서 covered=false의 의미는 두 가지다: ① 수집 창 밖(예: 3개월 브랜드의 3m_6m·6m_12m —
	 * 플랜 속성), ② 창 안이지만 상한 컷으로 수집이 거기까지 닿지 않음(고물량 브랜드의 깊은 구간 —
	 * 데이터 속성). FE가 구분해 표기하려면 계정 API의 {@code collectionCapped}·{@code coveredUntil}
	 * (계약 §10-1)을 함께 본다. 컷 없이 완주한 브랜드(coveredUntil null)는 종전과 동일하게 창
	 * 판정뿐이다 — 창 안 0건 버킷의 covered=true는 "수집했는데 게시물 없음" 그대로다.
	 *
	 * <p>false는 "이 구간 전체의 수집을 보장하지 못한다"는 뜻이다. 집계 모수는 실수집
	 * 범위로 클램프된다(2026-08-20 결정 — 컷 밖 tagged 기수집분은 성과 집계에서 제외,
	 * {@code BrandPostAssembler#assembleBrandPosts}의 capToCoverage) — 그래도 direct 콘텐츠
	 * (상한 면제)와 컷 경계 버킷의 커버된 쪽 게시물은 실리므로 covered=false ∧
	 * contentCount&gt;0은 여전히 정상 조합이다.
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
