package com.celfit.was.v1.perfdashboard;

import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.v1.brandmonitoring.BrandAccountType;
import com.celfit.was.v1.common.KstTimestamps;
import com.celfit.was.v1.monitoring.TrackingItemResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 성과 비교 집계 조립(스펙 2026-08-10) — 목록 API가 조립한 전량(필터 적용 후)을 받아 브랜드
 * 계정 × 5구간으로 합산한다. 구간 산출·합산은 전부 정적 순수 함수라 DB 없이 단위 테스트한다.
 */
@Component
public class PerformanceComparisonAssembler {

	private static final Logger log = LoggerFactory.getLogger(PerformanceComparisonAssembler.class);

	private final BrandLinkRepository linkRepository;
	private final Optional<BrandReadRepository> brandReadRepository;

	public PerformanceComparisonAssembler(BrandLinkRepository linkRepository,
			Optional<BrandReadRepository> brandReadRepository) {
		this.linkRepository = linkRepository;
		this.brandReadRepository = brandReadRepository;
	}

	/** 컨트롤러 진입점 — contents는 분류 필터(source·sponsorship·campaignId) 적용 후 전량. */
	public PerformanceComparisonResponse assemble(long userId, List<PerformanceContentResponse> contents) {
		return assemble(userId, contents, LocalDate.now(KstTimestamps.KST));
	}

	/**
	 * 시각 주입 오버로드(테스트용). 계정 축은 활성 브랜드 연결 순서 그대로다 — 콘텐츠 0건 계정도
	 * 실린다(비교 화면의 축은 "연결된 계정"이지 "콘텐츠 있는 계정"이 아니다).
	 * individual(brandAccountId null)은 계정 귀속이 불가능해 어느 막대에도 안 든다(스펙 §집계 규칙
	 * — source=individual 필터 시 전 구간이 비는 것은 의도된 동작).
	 */
	PerformanceComparisonResponse assemble(long userId, List<PerformanceContentResponse> contents,
			LocalDate today) {
		if (brandReadRepository.isEmpty()) {
			return new PerformanceComparisonResponse(List.of());   // monitoring 비활성 — 비교 축 없음
		}
		List<BucketRange> ranges = bucketRanges(today);
		Map<String, List<PerformanceContentResponse>> byBrand = contents.stream()
				.filter(c -> c.brandAccountId() != null)
				.collect(Collectors.groupingBy(PerformanceContentResponse::brandAccountId));

		List<PerformanceComparisonResponse.AccountComparison> accounts = new ArrayList<>();
		for (BrandLinkRow link : linkRepository.findAllActiveByUser(userId)) {
			Optional<BrandAccountRow> account = brandReadRepository.get().findAccount(link.brandId());
			if (account.isEmpty()) {
				// 연결은 살아 있는데 monitoring 계정 행이 없는 상태 — 목록 API와 동일하게 그 계정만 뺀다.
				log.warn("브랜드 연결의 monitoring 계정 행 부재 — 비교 축 생략 userId={}, brandId={}",
						userId, link.brandId());
				continue;
			}
			// accountType은 계정이 아니라 링크(구독)의 속성이라 순회 중인 링크 행에서 온다(08-12).
			accounts.add(compare(account.get(), BrandAccountType.orDefault(link.accountType()),
					byBrand.getOrDefault(String.valueOf(link.brandId()), List.of()), ranges, today));
		}
		return new PerformanceComparisonResponse(List.copyOf(accounts));
	}

	/** 구간 1개(양끝 포함) — 업로드일이 [from, to]에 들면 귀속. */
	record BucketRange(String key, LocalDate from, LocalDate to) {
	}

	/**
	 * FE 표 그대로의 5구간(서로 안 겹침, 업로드일 기준·KST 달력일). 달력월 연산은
	 * {@link LocalDate#minusMonths}(말일 클램프)라 월말 기준일에도 경계가 역전되지 않는다.
	 */
	static List<BucketRange> bucketRanges(LocalDate today) {
		return List.of(
				new BucketRange("1w", today.minusDays(6), today),
				new BucketRange("1w_1m", today.minusMonths(1), today.minusDays(7)),
				new BucketRange("1m_3m", today.minusMonths(3), today.minusMonths(1).minusDays(1)),
				new BucketRange("3m_6m", today.minusMonths(6), today.minusMonths(3).minusDays(1)),
				new BucketRange("6m_12m", today.minusMonths(12), today.minusMonths(6).minusDays(1)));
	}

	/**
	 * 계정 1개 집계 — accountContents는 이미 이 계정으로 귀속된 콘텐츠만 받는다(그룹핑은 호출부).
	 * covered는 <b>버킷별</b> 판정이다(collectionMonths 스펙 2026-08-12): 백필이 열거하는 범위가
	 * collection_months 창뿐이라, 완주해도 창 밖 버킷은 수집한 적 자체가 없다 — 계정 단위 true는
	 * 3개월 브랜드의 3m_6m·6m_12m을 "게시물 없음"으로 오보한다(#454 리뷰 ②). 판정 3중 AND:
	 * <ul>
	 * <li><b>완주</b>(backfillCompletedAt 존재) — last_swept_at은 첫 페이지 배치만 정산돼도 미리
	 * 찍히므로 못 쓴다(스트리밍 백필). 08-13 개정으로 기간 확장이 이 값을 NULL로 리셋하므로,
	 * 확장 중 전 구간 보수적 false는 이 조건 하나로 성립한다(BrandRepository.expandWindow).</li>
	 * <li><b>확장 중 아님</b>(lastSweptOn 존재) — 확장이 완주 시각을 리셋하기 전(08-12)에는 이쪽이
	 * 확장 판별의 본체였다. 지금은 위 조건과 중복이지만, "이번 창 기준 완주"라는 독립 근거라
	 * 남겨 둔다 — 창을 다시 여는 어떤 경로가 완주 시각을 남기더라도 보수적 false가 유지된다.</li>
	 * <li><b>버킷이 창 안</b> — 먼 쪽 경계(from)가 창 하한(today.minusMonths(collectionMonths))
	 * 이상. 부분 겹침은 false(보수적), 경계일은 포함 — 창 하한과 버킷 하한이 같은
	 * minusMonths(말일 클램프) 연산이라 12개월 계정의 6m_12m이 정확히 경계에 얹힌다.</li>
	 * </ul>
	 * false여도 집계값은 그대로 내린다(direct는 레거시 파이프라인이라 스윕 전에도 존재할 수 있다).
	 */
	static PerformanceComparisonResponse.AccountComparison compare(BrandAccountRow account, String accountType,
			List<PerformanceContentResponse> accountContents, List<BucketRange> ranges, LocalDate today) {
		boolean accountCovered = account.backfillCompletedAt() != null && account.lastSweptOn() != null;
		LocalDate windowStart = today.minusMonths(account.collectionMonths());
		List<PerformanceComparisonResponse.Bucket> buckets = new ArrayList<>(ranges.size());
		for (BucketRange range : ranges) {
			boolean covered = accountCovered && !range.from().isBefore(windowStart);
			buckets.add(aggregate(range, covered, accountContents));
		}
		// collectionStartedAt은 브랜드 계정 API와 같은 앵커(collection_started_at, 확장 시 갱신) —
		// registered_at을 쓰면 같은 이름의 필드가 API마다 다른 시각을 가리키게 된다.
		return new PerformanceComparisonResponse.AccountComparison(String.valueOf(account.id()),
				account.username(), accountType, KstTimestamps.toKstIso(account.collectionStartedAt()),
				List.copyOf(buckets));
	}

	/**
	 * 구간 1개 합산 — 지표는 콘텐츠별 <b>최신 스냅샷</b>(날짜 오름차순 계약이라 마지막 원소 — 목록의
	 * commentsTotal과 같은 관용구). 합은 non-null만 더하고 non-null이 하나도 없으면(0건 포함) null:
	 * 합 0(전부 관측됐는데 0)과 null(전부 미제공)을 FE가 다르게 그린다(규칙 ③ — 피드는 views 항상 null).
	 */
	private static PerformanceComparisonResponse.Bucket aggregate(BucketRange range, boolean covered,
			List<PerformanceContentResponse> contents) {
		int contentCount = 0;
		Long views = null;
		Long likes = null;
		Long comments = null;
		Long followersSum = null;
		int viewsMissing = 0;
		int likesHidden = 0;
		int followersMissing = 0;
		for (PerformanceContentResponse content : contents) {
			LocalDate uploadedOn = PerformanceContentAssembler.uploadedOn(content);
			// 업로드일 미상(post 없는 collecting 등)·구간 밖은 어느 구간에도 안 든다(스펙 §구간).
			if (uploadedOn == null || uploadedOn.isBefore(range.from()) || uploadedOn.isAfter(range.to())) {
				continue;
			}
			contentCount++;

			TrackingItemResponse.SnapshotResponse latest = latestSnapshot(content);
			views = accumulate(views, latest == null ? null : latest.views());
			likes = accumulate(likes, latest == null ? null : latest.likes());
			comments = accumulate(comments, latest == null ? null : latest.comments());
			followersSum = accumulate(followersSum, content.item().followers());

			if (latest == null || latest.views() == null) {
				viewsMissing++;
			}
			// 숨김은 관측이 있어야 셀 수 있다 — 스냅샷 자체가 없으면 결측이지 숨김이 아니다.
			if (latest != null && latest.likesHidden()) {
				likesHidden++;
			}
			if (content.item().followers() == null) {
				followersMissing++;
			}
		}
		return new PerformanceComparisonResponse.Bucket(range.key(), covered, contentCount,
				views, likes, comments, followersSum, viewsMissing, likesHidden, followersMissing);
	}

	/** null 유지 합산 — 첫 non-null 값에서 합이 시작되고, value가 null이면 sum을 건드리지 않는다. */
	private static Long accumulate(Long sum, Long value) {
		if (value == null) {
			return sum;
		}
		return sum == null ? value : sum + value;
	}

	/** 스냅샷은 날짜 오름차순 계약 — 마지막이 최신. post가 없거나 스냅샷 0개면 null(관측 전무). */
	private static TrackingItemResponse.SnapshotResponse latestSnapshot(PerformanceContentResponse content) {
		var post = content.item().post();
		if (post == null || post.snapshots() == null || post.snapshots().isEmpty()) {
			return null;
		}
		return post.snapshots().get(post.snapshots().size() - 1);
	}
}
